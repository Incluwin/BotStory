package client;

import client.command.StatResetCalculator;
import constants.skills.Hero;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.StatEffect;
import tools.DatabaseConnection;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Character.adminSetLevel on the reflective fixture of CharacterFixtureSkillRestorationTest (no map, party or guild). */
class CharacterAdminSetLevelTest {
    @Test
    void crewfriendRaisedToFighterFortyIsHealedToHisNewMaximumWithFifteenApAndNinetyTwoSp() throws Exception {
        Character crewfriend = newCharacter(Job.BEGINNER, 37, 161, 36, 4, 4, 0);
        crewfriend.setMaxHp(500);
        crewfriend.setMaxMp(100);
        crewfriend.updateHpMp(120, 30);
        crewfriend.setExp(1234);

        LevelSetPlan.AdminLevelChange change = crewfriend.adminSetLevel(new LevelSetPlan.Ok(Job.FIGHTER, 40, false), null);

        assertEquals(40, crewfriend.getLevel());
        assertEquals(Job.FIGHTER, crewfriend.getJob());
        assertEquals(0, crewfriend.getExp());
        assertEquals(List.of(161, 36, 4, 4), stats(crewfriend));
        assertEquals(15, crewfriend.getRemainingAp());
        assertEquals(92, crewfriend.getRemainingSps()[0]);
        HpMpGrowthPolicy.Growth base = HpMpGrowthPolicy.baseForJobAtLevel(Job.FIGHTER, 40);
        assertEquals(base.hp(), crewfriend.getMaxHp());
        assertEquals(base.mp(), crewfriend.getMaxMp());
        assertEquals(crewfriend.getCurrentMaxHp(), crewfriend.getHp());
        assertEquals(crewfriend.getCurrentMaxMp(), crewfriend.getMp());
        assertEquals(new LevelSetPlan.AdminLevelChange(37, Job.BEGINNER, new LevelSetPlan.Stats(161, 36, 4, 4, 0),
                40, Job.FIGHTER, 15, 92, 0, List.of()), change);
    }

    @Test
    void aWarriorKeepsItsFirstJobSkillsAndGetsTheNewSpOnTop() throws Exception {
        Character warrior = newCharacter(Job.WARRIOR, 30, 100, 40, 4, 4, 9);
        warrior.restoreFixtureSkillLevel(skill(1001004, 20), 20, 0);
        warrior.restoreFixtureSkillLevel(skill(1001005, 20), 20, 0);
        warrior.restoreFixtureSkillLevel(skill(1000000, 16), 16, 0);
        warrior.restoreFixtureSkillLevel(skill(1000002, 8), 5, 0);

        LevelSetPlan.AdminLevelChange change = warrior.adminSetLevel(new LevelSetPlan.Ok(Job.FIGHTER, 40, false), null);

        assertEquals(31, warrior.getRemainingSps()[0]);
        assertEquals(4, change.skillsKept());
        assertEquals(4, warrior.getSkills().size());
        assertEquals(204 - (96 + 36), warrior.getRemainingAp());
    }

    @Test
    void loweringPastTheTotalResetsToTheFloorsAndHealsToTheLowerMaximum() throws Exception {
        Character fighter = newCharacter(Job.FIGHTER, 40, 160, 40, 4, 4, 8);
        fighter.setMaxHp(2000);
        fighter.updateHpMp(2000, 100);

        fighter.adminSetLevel(new LevelSetPlan.Ok(Job.FIGHTER, 30, false), null);

        assertEquals(List.of(35, 4, 4, 4), stats(fighter));
        assertEquals(154 - 31, fighter.getRemainingAp());
        assertEquals(62, fighter.getRemainingSps()[0]);
        assertEquals(HpMpGrowthPolicy.baseForJobAtLevel(Job.FIGHTER, 30).hp(), fighter.getMaxHp());
        assertEquals(fighter.getCurrentMaxHp(), fighter.getHp());
    }

    @Test
    void aRestoreGivesTheRecordedStatsBack() throws Exception {
        Character fighter = newCharacter(Job.FIGHTER, 40, 161, 36, 4, 4, 15);

        // recorded before the first set: 189 points as 150/47 (kept stats would have stayed 161/36)
        fighter.adminSetLevel(new LevelSetPlan.Ok(Job.BEGINNER, 37, true), new LevelSetPlan.Stats(150, 47, 4, 4, 0));

        assertEquals(List.of(150, 47, 4, 4), stats(fighter));
        assertEquals(0, fighter.getRemainingAp());
        assertEquals(0, fighter.getRemainingSps()[0]);
        assertEquals(Job.BEGINNER, fighter.getJob());
        assertEquals(37, fighter.getLevel());
    }

    @Test
    void aRestoreGivesTheRecordedExpBackBelowWhatTheNextLevelNeeds() throws Exception {
        Character fighter = newCharacter(Job.FIGHTER, 40, 161, 36, 4, 4, 15);
        fighter.setExp(5000);

        fighter.adminSetLevel(new LevelSetPlan.Ok(Job.BEGINNER, 37, true, 1234), new LevelSetPlan.Stats(150, 47, 4, 4, 0));
        assertEquals(1234, fighter.getExp());

        fighter.adminSetLevel(new LevelSetPlan.Ok(Job.FIGHTER, 40, false), null);
        assertEquals(0, fighter.getExp()); // a set that is no restore starts the level afresh

        fighter.adminSetLevel(new LevelSetPlan.Ok(Job.BEGINNER, 37, true, Integer.MAX_VALUE), null);
        assertEquals(constants.game.ExpTable.getExpNeededForLevel(37) - 1, fighter.getExp());
    }

    @Test
    void aFighterSetBackToWarriorLosesItsFighterSkillsAndKeepsTheRestOfTheSp() throws Exception {
        Character fighter = newCharacter(Job.FIGHTER, 40, 100, 40, 4, 4, 0);
        fighter.restoreFixtureSkillLevel(skill(1000000, 16), 16, 0); // Improved HP Recovery
        fighter.restoreFixtureSkillLevel(skill(1001004, 20), 20, 0); // Power Strike
        fighter.restoreFixtureSkillLevel(skill(1001005, 20), 20, 0); // Slash Blast
        fighter.restoreFixtureSkillLevel(skill(1100000, 20), 10, 0); // Sword Mastery
        fighter.restoreFixtureSkillLevel(skill(1101004, 20), 10, 0); // Sword Booster
        List<String> deleted = new ArrayList<>();

        LevelSetPlan.AdminLevelChange change;
        try (MockedStatic<DatabaseConnection> database = deletesInto(deleted)) {
            change = fighter.adminSetLevel(new LevelSetPlan.Ok(Job.WARRIOR, 30, false), null);
        }

        assertEquals(Map.of(1000000, 16, 1001004, 20, 1001005, 20), levels(fighter));
        assertEquals(List.of(1100000, 1101004), change.dropped());
        assertEquals(List.of("DELETE FROM skills WHERE skillid = ? AND characterid = ?",
                "DELETE FROM skills WHERE skillid = ? AND characterid = ?"), deleted);
        assertEquals(StatResetCalculator.totalSp(Job.WARRIOR, 30) - 56, fighter.getRemainingSps()[0]);
    }

    @Test
    void aKeptImprovedMaxHpIncreaseAddsItsBonusForEveryLevelPastTen() throws Exception {
        Character fighter = newCharacter(Job.FIGHTER, 40, 100, 40, 4, 4, 0);
        Skill improvedMaxHp = skill(1000001, 10); // Warrior.IMPROVED_MAXHP: +x max HP a level past 10
        StatEffect effect = mock(StatEffect.class);
        when(effect.getX()).thenReturn(20);
        when(improvedMaxHp.getEffect(10)).thenReturn(effect);
        fighter.restoreFixtureSkillLevel(improvedMaxHp, 10, 0);

        try (MockedStatic<SkillFactory> skills = mockStatic(SkillFactory.class)) {
            skills.when(() -> SkillFactory.getSkill(1000001)).thenReturn(improvedMaxHp);
            fighter.adminSetLevel(new LevelSetPlan.Ok(Job.FIGHTER, 45, false), null);
        }

        assertEquals(HpMpGrowthPolicy.baseForJobAtLevel(Job.FIGHTER, 45).hp() + 20 * (45 - 10), fighter.getMaxHp());
        assertEquals(fighter.getCurrentMaxHp(), fighter.getHp());
    }

    @Test
    void aCrusaderRaisedToHeroGetsTheMasterLevelsTheAdvancementGives() throws Exception {
        Character crusader = newCharacter(Job.CRUSADER, 119, 400, 100, 4, 4, 0);

        try (MockedStatic<SkillFactory> skills = heroMasteries(new ArrayList<>())) {
            crusader.adminSetLevel(new LevelSetPlan.Ok(Job.HERO, 120, false), null);
        }

        assertEquals(Map.of(Hero.ACHILLES, 10, Hero.MONSTER_MAGNET, 10, Hero.BRANDISH, 10), masterLevels(crusader));
        assertEquals(Map.of(Hero.ACHILLES, 0, Hero.MONSTER_MAGNET, 0, Hero.BRANDISH, 0), levels(crusader));
    }

    @Test
    void aHeroWhoseFourthJobTierIsDroppedKeepsTheMasterLevelsOfItsOwnJob() throws Exception {
        Character hero = newCharacter(Job.HERO, 125, 400, 100, 4, 4, 0);
        int total = StatResetCalculator.totalSp(Job.HERO, 125);
        hero.restoreFixtureSkillLevel(skill(1001004, 127), 61, 0); // Warrior
        hero.restoreFixtureSkillLevel(skill(1101004, 127), 90, 0); // Fighter
        hero.restoreFixtureSkillLevel(skill(1111002, 127), 120, 0); // Crusader
        List<Skill> masteries = new ArrayList<>();
        try (MockedStatic<SkillFactory> skills = heroMasteries(masteries)) {
            hero.restoreFixtureSkillLevel(masteries.get(0), 0, 10); // Achilles, never learned
            hero.restoreFixtureSkillLevel(masteries.get(1), 0, 10); // Monster Magnet, never learned
            hero.restoreFixtureSkillLevel(masteries.get(2), total - 271, 10); // Brandish: every point left
            try (MockedStatic<DatabaseConnection> database = deletesInto(new ArrayList<>())) {
                // one level down: 3 SP fewer than the kept levels, so the whole fourth-job tier goes
                LevelSetPlan.AdminLevelChange change = hero.adminSetLevel(new LevelSetPlan.Ok(Job.HERO, 124, false),
                        null);
                assertTrue(change.dropped().contains(Hero.BRANDISH), change.dropped().toString());
            }
        }

        assertEquals(Map.of(Hero.ACHILLES, 10, Hero.MONSTER_MAGNET, 10, Hero.BRANDISH, 10, 1001004, 0, 1101004, 0,
                1111002, 0), masterLevels(hero));
        assertEquals(0, levels(hero).get(Hero.BRANDISH));
        assertEquals(StatResetCalculator.totalSp(Job.HERO, 124) - 271,
                hero.getRemainingSps()[constants.game.GameConstants.getSkillBook(Job.HERO.getId())]);
    }

    @Test
    void thePartyEntryCarriesTheNewLevelAndJob() throws Exception {
        Character crewfriend = newCharacter(Job.BEGINNER, 37, 161, 36, 4, 4, 0);
        assertEquals(37, crewfriend.getMPC().getLevel()); // the entry a party quest's level check reads

        crewfriend.adminSetLevel(new LevelSetPlan.Ok(Job.FIGHTER, 40, false), null);

        assertEquals(40, crewfriend.getMPC().getLevel());
        assertEquals(Job.FIGHTER.getId(), crewfriend.getMPC().getJobId());
    }

    /** Every skill row the drop deletes, by statement, into {@code deleted}. */
    private static MockedStatic<DatabaseConnection> deletesInto(List<String> deleted) {
        MockedStatic<DatabaseConnection> database = mockStatic(DatabaseConnection.class);
        database.when(DatabaseConnection::getConnection).thenAnswer(invocation -> {
            Connection connection = mock(Connection.class);
            when(connection.prepareStatement(anyString())).thenAnswer(prepare -> {
                deleted.add(prepare.getArgument(0));
                return mock(PreparedStatement.class);
            });
            return connection;
        });
        return database;
    }

    /** SkillFactory with the three skills setMasteries(112) grants, also put into {@code skills} in that order. */
    private static MockedStatic<SkillFactory> heroMasteries(List<Skill> skills) {
        MockedStatic<SkillFactory> factory = mockStatic(SkillFactory.class);
        for (int id : new int[] {Hero.ACHILLES, Hero.MONSTER_MAGNET, Hero.BRANDISH}) {
            Skill skill = skill(id, 127);
            skills.add(skill);
            factory.when(() -> SkillFactory.getSkill(id)).thenReturn(skill);
        }
        return factory;
    }

    private static Map<Integer, Integer> levels(Character chr) {
        Map<Integer, Integer> levels = new TreeMap<>();
        chr.getSkills().forEach((skill, entry) -> levels.put(skill.getId(), (int) entry.skillevel));
        return levels;
    }

    private static Map<Integer, Integer> masterLevels(Character chr) {
        Map<Integer, Integer> levels = new TreeMap<>();
        chr.getSkills().forEach((skill, entry) -> levels.put(skill.getId(), entry.masterlevel));
        return levels;
    }

    private static List<Integer> stats(Character chr) {
        return List.of(chr.getStr(), chr.getDex(), chr.getInt(), chr.getLuk());
    }

    private static Skill skill(int id, int maximumLevel) {
        Skill skill = mock(Skill.class);
        when(skill.getId()).thenReturn(id);
        when(skill.getMaxLevel()).thenReturn(maximumLevel);
        when(skill.isBeginnerSkill()).thenReturn(false);
        when(skill.getEffect(anyInt())).thenReturn(mock(StatEffect.class)); // a drop reads its passive bonus (x = 0)
        return skill;
    }

    private static Character newCharacter(Job job, int level, int str, int dex, int intel, int luk, int ap)
            throws Exception {
        Constructor<Character> constructor = Character.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Character chr = constructor.newInstance();
        set(chr, "client", mock(Client.class));
        set(chr, "canRecvPartySearchInvite", false);
        set(chr, "equipchanged", false); // nothing worn: recalcLocalStats skips ItemInformationProvider (database)
        chr.setJob(job);
        chr.setLevel(level);
        chr.updateStrDexIntLuk(str, dex, intel, luk, ap);
        return chr;
    }

    private static void set(Character chr, String name, Object value) throws Exception {
        Field field = Character.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(chr, value);
    }
}
