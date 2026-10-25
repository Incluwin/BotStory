package server.living.population;

import client.Job;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A citizen's career names its second job; its life goes on along that branch to the fourth (2026-10-06). The first and
 * second job come with the catch-up, the third and fourth are errands, and at rollout a citizen already old enough for
 * one is a backlog citizen whose overdue job comes off-screen once.
 */
class CitizenJobPathTest {

    @Test
    void aSecondJobCareerLeadsAlongItsOwnBranchToTheFourthJob() {
        assertEquals(Job.HERO, CitizenJobPath.line(Job.FIGHTER));
        assertEquals(Job.DARKKNIGHT, CitizenJobPath.line(Job.SPEARMAN));
        assertEquals(Job.BISHOP, CitizenJobPath.line(Job.CLERIC));
        assertEquals(Job.CORSAIR, CitizenJobPath.line(Job.GUNSLINGER));
        assertEquals(Job.WARRIOR, CitizenJobPath.line(Job.WARRIOR), "a first-job career is its own end");
        assertEquals(Job.CRUSADER, CitizenJobPath.line(Job.CRUSADER), "so is a third-job one");
    }

    @Test
    void theCatchUpShapesTowardTheCareerJobAndNeverTakesAThirdJobByItself() {
        assertEquals(Job.FIGHTER, CitizenJobPath.shapeTarget(Job.FIGHTER, Job.BEGINNER, false));
        assertEquals(Job.FIGHTER, CitizenJobPath.shapeTarget(Job.FIGHTER, Job.FIGHTER, false));
        assertEquals(Job.CRUSADER, CitizenJobPath.shapeTarget(Job.FIGHTER, Job.CRUSADER, false),
                "a citizen past its career job keeps the job it holds: the next one is an errand");
        assertEquals(Job.FIGHTER, CitizenJobPath.shapeTarget(Job.FIGHTER, Job.WHITEKNIGHT, false),
                "a job off its line is not taken for a later step");
    }

    @Test
    void offScreenTheCatchUpShapesAlongTheWholeLine() {
        assertEquals(Job.HERO, CitizenJobPath.shapeTarget(Job.FIGHTER, Job.FIGHTER, true));
        assertEquals(Job.BISHOP, CitizenJobPath.shapeTarget(Job.CLERIC, Job.MAGICIAN, true));
    }

    @Test
    void anErrandIsTheNextThirdOrFourthJobStepTheGameAllowsNow() {
        assertNull(CitizenJobPath.errandStep(Job.FIGHTER, Job.FIGHTER, 69));
        assertEquals(Job.CRUSADER, CitizenJobPath.errandStep(Job.FIGHTER, Job.FIGHTER, 70));
        assertEquals(Job.PRIEST, CitizenJobPath.errandStep(Job.CLERIC, Job.CLERIC, 85));
        assertNull(CitizenJobPath.errandStep(Job.FIGHTER, Job.CRUSADER, 119));
        assertEquals(Job.HERO, CitizenJobPath.errandStep(Job.FIGHTER, Job.CRUSADER, 120));
        assertNull(CitizenJobPath.errandStep(Job.FIGHTER, Job.WARRIOR, 75), "the second job is the catch-up's, never an errand");
        assertNull(CitizenJobPath.errandStep(Job.FIGHTER, Job.BEGINNER, 75));
        assertNull(CitizenJobPath.errandStep(Job.FIGHTER, Job.HERO, 150), "nothing after the fourth job");
        assertNull(CitizenJobPath.errandStep(Job.FIGHTER, Job.WHITEKNIGHT, 120), "off its line: no errand");
    }

    @Test
    void overdueMeansOldEnoughForAThirdOrFourthJobItDoesNotHold() {
        assertTrue(CitizenJobPath.overdue(Job.FIGHTER, Job.FIGHTER, 70));
        assertTrue(CitizenJobPath.overdue(Job.CLERIC, Job.MAGICIAN, 72), "still at first job: its catch-up brings the second first");
        assertTrue(CitizenJobPath.overdue(Job.FIGHTER, Job.CRUSADER, 121));
        assertFalse(CitizenJobPath.overdue(Job.FIGHTER, Job.FIGHTER, 69));
        assertFalse(CitizenJobPath.overdue(Job.FIGHTER, Job.CRUSADER, 85));
        assertFalse(CitizenJobPath.overdue(Job.FIGHTER, null, 85));
    }
}
