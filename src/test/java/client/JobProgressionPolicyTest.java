package client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobProgressionPolicyTest {
    @Test
    void permitsOnlyDirectLevelEligibleExplorerAdvancements() {
        assertTrue(JobProgressionPolicy.isLegalAdvancement(Job.BEGINNER, Job.MAGICIAN, 8));
        assertTrue(JobProgressionPolicy.isLegalAdvancement(Job.PIRATE, Job.BRAWLER, 200));
        assertTrue(JobProgressionPolicy.isLegalAdvancement(Job.BRAWLER, Job.MARAUDER, 200));
        assertFalse(JobProgressionPolicy.isLegalAdvancement(Job.BEGINNER, Job.WARRIOR, 9));
        assertFalse(JobProgressionPolicy.isLegalAdvancement(Job.WARRIOR, Job.WARRIOR, 200));
        assertFalse(JobProgressionPolicy.isLegalAdvancement(Job.WARRIOR, Job.HERO, 200));
        assertFalse(JobProgressionPolicy.isLegalAdvancement(Job.WARRIOR, Job.MAGICIAN, 200));
    }

    @Test
    void permitsTheCygnusKnightsToThirdJobAndAranToFourth() {
        assertTrue(JobProgressionPolicy.isLegalAdvancement(Job.NOBLESSE, Job.DAWNWARRIOR1, 10));
        assertTrue(JobProgressionPolicy.isLegalAdvancement(Job.BLAZEWIZARD1, Job.BLAZEWIZARD2, 30));
        assertTrue(JobProgressionPolicy.isLegalAdvancement(Job.THUNDERBREAKER2, Job.THUNDERBREAKER3, 70));
        assertTrue(JobProgressionPolicy.isLegalAdvancement(Job.LEGEND, Job.ARAN1, 10));
        assertTrue(JobProgressionPolicy.isLegalAdvancement(Job.ARAN3, Job.ARAN4, 120));
        assertFalse(JobProgressionPolicy.isLegalAdvancement(Job.NOBLESSE, Job.DAWNWARRIOR1, 9));
        assertFalse(JobProgressionPolicy.isLegalAdvancement(Job.BEGINNER, Job.DAWNWARRIOR1, 10));
        assertFalse(JobProgressionPolicy.isLegalAdvancement(Job.NOBLESSE, Job.WARRIOR, 10));
        assertFalse(JobProgressionPolicy.isLegalAdvancement(Job.DAWNWARRIOR3, Job.DAWNWARRIOR4, 120)); // no v83 skills
        assertFalse(JobProgressionPolicy.isLegalAdvancement(Job.EVAN, Job.EVAN1, 10));
    }

    @Test
    void namesEachLinesBeginnerAndLastJob() {
        assertEquals(Job.BEGINNER, JobProgressionPolicy.beginnerOf(Job.HERO));
        assertEquals(Job.NOBLESSE, JobProgressionPolicy.beginnerOf(Job.NIGHTWALKER2));
        assertEquals(Job.LEGEND, JobProgressionPolicy.beginnerOf(Job.ARAN1));
        assertEquals(Job.HERO, JobProgressionPolicy.lastJobOfLine(Job.FIGHTER));
        assertEquals(Job.DAWNWARRIOR3, JobProgressionPolicy.lastJobOfLine(Job.DAWNWARRIOR2));
        assertEquals(Job.WINDARCHER3, JobProgressionPolicy.lastJobOfLine(Job.WINDARCHER3));
        assertEquals(Job.ARAN4, JobProgressionPolicy.lastJobOfLine(Job.ARAN2));
        assertEquals(Job.WARRIOR, JobProgressionPolicy.lastJobOfLine(Job.WARRIOR)); // its branch is still open
        assertEquals(Job.NOBLESSE, JobProgressionPolicy.lastJobOfLine(Job.NOBLESSE)); // five classes to choose from
        assertEquals(Job.BLAZEWIZARD3, JobProgressionPolicy.lastJobOfLine(Job.BLAZEWIZARD1)); // one way on
        assertEquals(Job.ARAN4, JobProgressionPolicy.lastJobOfLine(Job.LEGEND));
        assertEquals(Job.ARAN4, JobProgressionPolicy.lastJobOfLine(Job.ARAN1));
        assertEquals(Job.ARAN4, JobProgressionPolicy.lastJobOfLine(Job.ARAN4));
        assertEquals(Job.EVAN2, JobProgressionPolicy.lastJobOfLine(Job.EVAN2));
    }

    @Test
    void definesHardClassLevelCaps() {
        assertEquals(200, JobProgressionPolicy.classLevelCap(Job.HERO, false));
        assertEquals(120, JobProgressionPolicy.classLevelCap(Job.DAWNWARRIOR1, false));
        assertEquals(255, JobProgressionPolicy.classLevelCap(Job.HERO, true));
    }
}
