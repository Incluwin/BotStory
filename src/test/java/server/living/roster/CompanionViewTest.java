package server.living.roster;

import client.Job;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The job a Crew window row or card header shows reads as the game names it, for every job there is. */
class CompanionViewTest {

    @Test
    void everyJobReadsAsTheGameNamesIt() {
        Map<Job, String> expected = new EnumMap<>(Job.class);
        put(expected, "Beginner", Job.BEGINNER);
        put(expected, "Warrior", Job.WARRIOR);
        put(expected, "Fighter", Job.FIGHTER);
        put(expected, "Crusader", Job.CRUSADER);
        put(expected, "Hero", Job.HERO);
        put(expected, "Page", Job.PAGE);
        put(expected, "White Knight", Job.WHITEKNIGHT);
        put(expected, "Paladin", Job.PALADIN);
        put(expected, "Spearman", Job.SPEARMAN);
        put(expected, "Dragon Knight", Job.DRAGONKNIGHT);
        put(expected, "Dark Knight", Job.DARKKNIGHT);
        put(expected, "Magician", Job.MAGICIAN);
        put(expected, "F/P Wizard", Job.FP_WIZARD);
        put(expected, "F/P Mage", Job.FP_MAGE);
        put(expected, "F/P Archmage", Job.FP_ARCHMAGE);
        put(expected, "I/L Wizard", Job.IL_WIZARD);
        put(expected, "I/L Mage", Job.IL_MAGE);
        put(expected, "I/L Archmage", Job.IL_ARCHMAGE);
        put(expected, "Cleric", Job.CLERIC);
        put(expected, "Priest", Job.PRIEST);
        put(expected, "Bishop", Job.BISHOP);
        put(expected, "Bowman", Job.BOWMAN);
        put(expected, "Hunter", Job.HUNTER);
        put(expected, "Ranger", Job.RANGER);
        put(expected, "Bowmaster", Job.BOWMASTER);
        put(expected, "Crossbowman", Job.CROSSBOWMAN);
        put(expected, "Sniper", Job.SNIPER);
        put(expected, "Marksman", Job.MARKSMAN);
        put(expected, "Thief", Job.THIEF);
        put(expected, "Assassin", Job.ASSASSIN);
        put(expected, "Hermit", Job.HERMIT);
        put(expected, "Night Lord", Job.NIGHTLORD);
        put(expected, "Bandit", Job.BANDIT);
        put(expected, "Chief Bandit", Job.CHIEFBANDIT);
        put(expected, "Shadower", Job.SHADOWER);
        put(expected, "Pirate", Job.PIRATE);
        put(expected, "Brawler", Job.BRAWLER);
        put(expected, "Marauder", Job.MARAUDER);
        put(expected, "Buccaneer", Job.BUCCANEER);
        put(expected, "Gunslinger", Job.GUNSLINGER);
        put(expected, "Outlaw", Job.OUTLAW);
        put(expected, "Corsair", Job.CORSAIR);
        put(expected, "Maple Leaf Brigadier", Job.MAPLELEAF_BRIGADIER);
        put(expected, "GM", Job.GM, Job.SUPERGM);
        put(expected, "Noblesse", Job.NOBLESSE);
        put(expected, "Dawn Warrior", Job.DAWNWARRIOR1, Job.DAWNWARRIOR2, Job.DAWNWARRIOR3, Job.DAWNWARRIOR4);
        put(expected, "Blaze Wizard", Job.BLAZEWIZARD1, Job.BLAZEWIZARD2, Job.BLAZEWIZARD3, Job.BLAZEWIZARD4);
        put(expected, "Wind Archer", Job.WINDARCHER1, Job.WINDARCHER2, Job.WINDARCHER3, Job.WINDARCHER4);
        put(expected, "Night Walker", Job.NIGHTWALKER1, Job.NIGHTWALKER2, Job.NIGHTWALKER3, Job.NIGHTWALKER4);
        put(expected, "Thunder Breaker", Job.THUNDERBREAKER1, Job.THUNDERBREAKER2, Job.THUNDERBREAKER3, Job.THUNDERBREAKER4);
        put(expected, "Legend", Job.LEGEND);
        put(expected, "Evan", Job.EVAN, Job.EVAN1, Job.EVAN2, Job.EVAN3, Job.EVAN4, Job.EVAN5, Job.EVAN6, Job.EVAN7,
                Job.EVAN8, Job.EVAN9, Job.EVAN10);
        put(expected, "Aran", Job.ARAN1, Job.ARAN2, Job.ARAN3, Job.ARAN4);

        assertEquals(Job.values().length, expected.size(), "a job constant without an expected name");
        for (Job job : Job.values()) {
            assertEquals(expected.get(job), CompanionView.jobName(job.getId()), job.name());
        }
    }

    @Test
    void anUnknownJobHasNoName() {
        assertEquals("", CompanionView.jobName(-1));
        assertEquals("", CompanionView.jobName(12345));
    }

    private static void put(Map<Job, String> expected, String name, Job... jobs) {
        for (Job job : jobs) {
            expected.put(job, name);
        }
    }
}
