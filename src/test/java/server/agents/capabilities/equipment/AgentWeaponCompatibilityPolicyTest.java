package server.agents.capabilities.equipment;

import client.Character;
import client.Job;
import client.inventory.WeaponType;
import constants.skills.Fighter;
import constants.skills.Page;
import constants.skills.Pirate;
import constants.skills.Spearman;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentWeaponCompatibilityPolicyTest {
    @Test
    void bowmanAcceptsBowAndCrossbowOnly() {
        Character agent = mock(Character.class);
        when(agent.getJob()).thenReturn(Job.BOWMAN);

        assertTrue(AgentWeaponCompatibilityPolicy.isWeaponCompatible(agent, WeaponType.BOW));
        assertTrue(AgentWeaponCompatibilityPolicy.isWeaponCompatible(agent, WeaponType.CROSSBOW));
        assertFalse(AgentWeaponCompatibilityPolicy.isWeaponCompatible(agent, WeaponType.CLAW));
    }

    @Test
    void pirateSkillsChooseGunOrKnuckle() {
        Character agent = mock(Character.class);
        when(agent.getJob()).thenReturn(Job.PIRATE);
        when(agent.getSkillLevel(Pirate.DOUBLE_SHOT)).thenReturn(1);
        when(agent.getSkillLevel(Pirate.FLASH_FIST)).thenReturn(0);
        when(agent.getSkillLevel(Pirate.SOMERSAULT_KICK)).thenReturn(0);

        assertTrue(AgentWeaponCompatibilityPolicy.isWeaponCompatible(agent, WeaponType.GUN));
        assertFalse(AgentWeaponCompatibilityPolicy.isWeaponCompatible(agent, WeaponType.KNUCKLE));
    }

    @Test
    void warriorSkillFamilyControlsTrackKey() {
        Character agent = mock(Character.class);
        when(agent.getJob()).thenReturn(Job.FIGHTER);
        when(agent.getSkillLevel(Fighter.SWORD_MASTERY)).thenReturn(1);
        when(agent.getSkillLevel(Fighter.SWORD_BOOSTER)).thenReturn(0);
        when(agent.getSkillLevel(Fighter.AXE_MASTERY)).thenReturn(0);
        when(agent.getSkillLevel(Fighter.AXE_BOOSTER)).thenReturn(0);

        assertEquals("sword", AgentWeaponCompatibilityPolicy.weaponUsefulnessTrackKey(agent, WeaponType.SWORD1H));
        assertNull(AgentWeaponCompatibilityPolicy.weaponUsefulnessTrackKey(agent, WeaponType.GENERAL1H_SWING));
    }

    @Test
    void aCrusaderWithOnlyItsFighterSwordSkillsTakesASwordNotAnAxe() {
        // Dorran 2026-10-31: Sword Mastery, Sword Booster, Final Attack: Sword; no Coma or Panic yet
        Map<Integer, Integer> skills = Map.of(Fighter.SWORD_MASTERY, 20, Fighter.SWORD_BOOSTER, 20,
                Fighter.FINAL_ATTACK_SWORD, 30);

        assertTrue(compatible(Job.CRUSADER, skills, WeaponType.SWORD1H));
        assertTrue(compatible(Job.HERO, skills, WeaponType.SWORD2H));
        assertFalse(compatible(Job.CRUSADER, skills, WeaponType.GENERAL1H_SWING));
        assertFalse(compatible(Job.CRUSADER, skills, WeaponType.GENERAL2H_SWING));
    }

    @Test
    void aPaladinsBluntChoiceIsReadFromItsPageSkills() {
        Map<Integer, Integer> skills = Map.of(Page.BW_MASTERY, 20, Page.FINAL_ATTACK_BW, 30);

        assertTrue(compatible(Job.WHITEKNIGHT, skills, WeaponType.GENERAL2H_SWING));
        assertTrue(compatible(Job.PALADIN, skills, WeaponType.GENERAL2H_SWING));
        assertFalse(compatible(Job.PALADIN, skills, WeaponType.SWORD2H));
    }

    @Test
    void aDragonKnightWithOnlySpearSkillsTakesNoPolearm() {
        Map<Integer, Integer> skills = Map.of(Spearman.SPEAR_MASTERY, 20, Spearman.FINAL_ATTACK_SPEAR, 30);

        assertTrue(compatible(Job.DRAGONKNIGHT, skills, WeaponType.SPEAR_STAB));
        assertFalse(compatible(Job.DARKKNIGHT, skills, WeaponType.POLE_ARM_SWING));
    }

    @Test
    void aWarriorWithNoWeaponSkillsYetTakesEitherFamily() {
        assertTrue(compatible(Job.CRUSADER, Map.of(), WeaponType.SWORD1H));
        assertTrue(compatible(Job.CRUSADER, Map.of(), WeaponType.GENERAL1H_SWING));
    }

    private static boolean compatible(Job job, Map<Integer, Integer> skills, WeaponType weaponType) {
        return AgentWeaponCompatibilityPolicy.isWeaponCompatible(job, skill -> skills.getOrDefault(skill, 0), weaponType);
    }

    @Test
    void mageJobsAreGrouped() {
        assertTrue(AgentWeaponCompatibilityPolicy.isMageJob(Job.MAGICIAN));
        assertTrue(AgentWeaponCompatibilityPolicy.isMageJob(Job.BISHOP));
        assertFalse(AgentWeaponCompatibilityPolicy.isMageJob(Job.ASSASSIN));
    }
}
