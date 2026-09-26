package server.agents.capabilities.combat;

import client.inventory.WeaponType;
import constants.skills.Paladin;
import constants.skills.Page;
import constants.skills.WhiteKnight;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCombatWeaponPolicyWarriorFamilyTest {
    @Test
    void aSwordPaladinCannotPickTheBluntCharge() {
        // The damage handler only grants the holy weakness for the sword charge id, so a blunt
        // charge on a sword is a buff that adds nothing.
        assertFalse(AgentCombatWeaponPolicy.canUseSkillWithWeapon(Paladin.BW_HOLY_CHARGE, WeaponType.SWORD1H));
        assertTrue(AgentCombatWeaponPolicy.canUseSkillWithWeapon(Paladin.SWORD_HOLY_CHARGE, WeaponType.SWORD1H));
    }

    @Test
    void aBluntPaladinTakesTheBluntCharge() {
        assertTrue(AgentCombatWeaponPolicy.canUseSkillWithWeapon(Paladin.BW_HOLY_CHARGE, WeaponType.GENERAL1H_SWING));
        assertFalse(AgentCombatWeaponPolicy.canUseSkillWithWeapon(Paladin.SWORD_HOLY_CHARGE, WeaponType.GENERAL1H_SWING));
    }

    @Test
    void boostersAndChargesFollowTheSameFamilyRule() {
        assertFalse(AgentCombatWeaponPolicy.canUseSkillWithWeapon(Page.BW_BOOSTER, WeaponType.SWORD2H));
        assertTrue(AgentCombatWeaponPolicy.canUseSkillWithWeapon(Page.SWORD_BOOSTER, WeaponType.SWORD2H));
        assertFalse(AgentCombatWeaponPolicy.canUseSkillWithWeapon(WhiteKnight.SWORD_ICE_CHARGE, WeaponType.GENERAL2H_SWING));
        assertTrue(AgentCombatWeaponPolicy.canUseSkillWithWeapon(WhiteKnight.BW_ICE_CHARGE, WeaponType.GENERAL2H_SWING));
    }
}
