package server.living.roster;

import client.Job;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Who gives an Explorer each job and where they stand, as in the stock scripts: the first and second job from the
 * class's town instructor, the third from the El Nath chiefs, the fourth from the Leafre elders.
 */
class JobInstructorsTest {

    @Test
    void firstAndSecondJobAreTheTownInstructors() {
        assertEquals(new JobInstructors.Instructor(1032001, 101000003, "Grendel", "Ellinia"),
                JobInstructors.of(Job.IL_WIZARD));
        assertEquals(JobInstructors.of(Job.IL_WIZARD), JobInstructors.of(Job.MAGICIAN));
        assertEquals(1022000, JobInstructors.of(Job.PAGE).npcId());
        assertEquals(102000003, JobInstructors.of(Job.WARRIOR).mapId());
        assertEquals(1012100, JobInstructors.of(Job.HUNTER).npcId());
        assertEquals(100000201, JobInstructors.of(Job.CROSSBOWMAN).mapId());
        assertEquals(1052001, JobInstructors.of(Job.BANDIT).npcId());
        assertEquals(103000003, JobInstructors.of(Job.ASSASSIN).mapId());
        assertEquals(1090000, JobInstructors.of(Job.GUNSLINGER).npcId());
        assertEquals(120000101, JobInstructors.of(Job.BRAWLER).mapId());
    }

    @Test
    void thirdJobIsElNathsChiefsResidenceAndFourthIsLeafresForestOfThePriest() {
        assertEquals(new JobInstructors.Instructor(2020008, 211000001, "Tylus", "El Nath"), JobInstructors.of(Job.CRUSADER));
        assertEquals(2020009, JobInstructors.of(Job.IL_MAGE).npcId());
        assertEquals(2020010, JobInstructors.of(Job.SNIPER).npcId());
        assertEquals(2020011, JobInstructors.of(Job.CHIEFBANDIT).npcId());
        assertEquals(2020013, JobInstructors.of(Job.OUTLAW).npcId());
        assertEquals(new JobInstructors.Instructor(2081100, 240010501, "Harmonia", "Leafre"), JobInstructors.of(Job.DARKKNIGHT));
        assertEquals(2081200, JobInstructors.of(Job.BISHOP).npcId());
        assertEquals(2081300, JobInstructors.of(Job.BOWMASTER).npcId());
        assertEquals(2081400, JobInstructors.of(Job.NIGHTLORD).npcId());
        assertEquals(2081500, JobInstructors.of(Job.CORSAIR).npcId());
    }

    @Test
    void everyExplorerAdvancementHasOneAndNothingElseDoes() {
        for (Job job : Job.values()) {
            boolean explorerAdvancement = job.getId() > 0 && job.getId() < 600;
            if (explorerAdvancement) {
                assertNotNull(JobInstructors.of(job), job.name());
            }
        }
        assertNull(JobInstructors.of(Job.BEGINNER));
        assertNull(JobInstructors.of(Job.DAWNWARRIOR2));
        assertNull(JobInstructors.of(Job.ARAN2));
        assertNull(JobInstructors.of(null));
    }

    @Test
    void theTierAsPlayersSayIt() {
        assertEquals("1st", JobInstructors.tier(Job.MAGICIAN));
        assertEquals("2nd", JobInstructors.tier(Job.IL_WIZARD));
        assertEquals("3rd", JobInstructors.tier(Job.IL_MAGE));
        assertEquals("4th", JobInstructors.tier(Job.IL_ARCHMAGE));
    }
}
