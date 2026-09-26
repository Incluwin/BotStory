package server.agents.capabilities.combat;

import client.status.MonsterStatus;
import constants.skills.Crusader;
import constants.skills.DragonKnight;
import constants.skills.Hero;
import constants.skills.WhiteKnight;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCrashSkillPolicyTest {
    @Test
    void recognisesTheThreeCrashSkillsOnly() {
        assertTrue(AgentCrashSkillPolicy.isCrashSkill(Crusader.ARMOR_CRASH));
        assertTrue(AgentCrashSkillPolicy.isCrashSkill(DragonKnight.POWER_CRASH));
        assertTrue(AgentCrashSkillPolicy.isCrashSkill(WhiteKnight.MAGIC_CRASH));
        assertFalse(AgentCrashSkillPolicy.isCrashSkill(Hero.BRANDISH));
    }

    @Test
    void armorCrashCancelsWeaponDefenceAndImmunitiesOnlyWithAntiImmunity() {
        assertEquals(Set.of(MonsterStatus.WEAPON_DEFENSE_UP),
                AgentCrashSkillPolicy.cancelledBy(Crusader.ARMOR_CRASH, false));
        assertEquals(Set.of(MonsterStatus.WEAPON_DEFENSE_UP, MonsterStatus.WEAPON_IMMUNITY, MonsterStatus.MAGIC_IMMUNITY),
                AgentCrashSkillPolicy.cancelledBy(Crusader.ARMOR_CRASH, true));
    }

    @Test
    void powerAndMagicCrashMapToTheirAttackBuffs() {
        assertEquals(Set.of(MonsterStatus.WEAPON_ATTACK_UP), AgentCrashSkillPolicy.cancelledBy(DragonKnight.POWER_CRASH, false));
        assertEquals(Set.of(MonsterStatus.MAGIC_ATTACK_UP), AgentCrashSkillPolicy.cancelledBy(WhiteKnight.MAGIC_CRASH, false));
        assertTrue(AgentCrashSkillPolicy.cancelledBy(Hero.BRANDISH, true).isEmpty());
    }

    @Test
    void nullTargetIsNeverWorthACrash() {
        assertFalse(AgentCrashSkillPolicy.worthCasting(Crusader.ARMOR_CRASH, null));
    }
}
