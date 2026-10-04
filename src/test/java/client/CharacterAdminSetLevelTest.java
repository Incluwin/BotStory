package client;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
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

    private static List<Integer> stats(Character chr) {
        return List.of(chr.getStr(), chr.getDex(), chr.getInt(), chr.getLuk());
    }

    private static Skill skill(int id, int maximumLevel) {
        Skill skill = mock(Skill.class);
        when(skill.getId()).thenReturn(id);
        when(skill.getMaxLevel()).thenReturn(maximumLevel);
        when(skill.isBeginnerSkill()).thenReturn(false);
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
