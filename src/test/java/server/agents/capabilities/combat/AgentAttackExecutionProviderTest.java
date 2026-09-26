package server.agents.capabilities.combat;

import client.BuffStat;
import client.Character;
import client.Skill;
import client.inventory.WeaponType;
import constants.skills.Bowmaster;
import constants.skills.Corsair;
import constants.skills.Magician;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentAttackExecutionProviderTest {
    @Test
    void magicCastUsesNormalSixAndOnlyBooster() {
        Skill skill = mock(Skill.class);
        Character agent = mock(Character.class);
        when(skill.getId()).thenReturn(Magician.MAGIC_CLAW);
        when(agent.getBuffedValue(BuffStat.BOOSTER)).thenReturn(-2);
        when(agent.getBuffedValue(BuffStat.SPEED_INFUSION)).thenReturn(-2);

        assertEquals(4, AgentAttackExecutionProvider.resolveSkillEffectiveAttackSpeed(skill, agent));
        assertEquals(6, AgentAttackExecutionProvider.resolveMagicAttackSpeed(null));
    }

    @Test
    void wandMagicFallbackUsesMagicCastActions() {
        Skill skill = mock(Skill.class);
        when(skill.getId()).thenReturn(Magician.MAGIC_CLAW);

        String action = AgentAttackExecutionProvider.resolveSkillAttackAction(
                null, skill, 1, WeaponType.WAND);

        assertTrue(Set.of("wand1", "wand2").contains(action));
    }

    @Test
    void rapidFireIsShotWithTheOnlyActionItsAfterimageDraws() {
        Skill rapidFire = new Skill(Corsair.RAPID_FIRE);
        rapidFire.setAfterimageActions("gun", List.of("shot"));  // Skill.wz 5221004/afterimage/gun

        // A gun's own swing is "handgun"; watching clients crashed on it.
        assertEquals("shot", AgentAttackExecutionProvider.afterimageSafeAction(rapidFire, "gun", "handgun"));
        assertEquals("shot", AgentAttackExecutionProvider.afterimageSafeAction(rapidFire, "gun", "shot"));
    }

    @Test
    void sampledActionIsKeptWhenTheSkillDrawsItOrHasNoAfterimageForTheWeapon() {
        Skill hurricane = new Skill(Bowmaster.HURRICANE);  // no afterimage node
        Skill bandOfThieves = new Skill(4211004);
        bandOfThieves.setAfterimageActions("swordOS", List.of("swingO1", "swingO2", "swingO3", "stabO1", "stabO2"));

        assertEquals("shoot1", AgentAttackExecutionProvider.afterimageSafeAction(hurricane, "bow", "shoot1"));
        assertEquals("stabO2", AgentAttackExecutionProvider.afterimageSafeAction(bandOfThieves, "swordOS", "stabO2"));
        assertEquals("stabO2", AgentAttackExecutionProvider.afterimageSafeAction(bandOfThieves, null, "stabO2"));
    }
}
