package server.agents.capabilities.combat;

import client.Character;
import client.Skill;
import client.SkillFactory;
import client.inventory.WeaponType;
import constants.skills.Archer;
import constants.skills.Bishop;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.StatEffect;
import server.life.Element;
import server.life.ElementalEffectiveness;
import server.life.Monster;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class AgentCombatPlanRuntimeTest {
    @Test
    void magicSkillMakesBasicWandAttackAFallbackRatherThanCompetitor() {
        AgentAttackPlan energyBolt = plan(2001004, AgentAttackRoute.MAGIC);
        AgentAttackPlan wandSwing = plan(0, AgentAttackRoute.CLOSE);

        assertTrue(AgentCombatPlanRuntime.hasUsableMagicSkill(List.of(energyBolt, wandSwing)));
    }

    @Test
    void rangedAndCloseSkillsDoNotSuppressBasicFallback() {
        assertFalse(AgentCombatPlanRuntime.hasUsableMagicSkill(List.of(
                plan(3001004, AgentAttackRoute.RANGED),
                plan(1001004, AgentAttackRoute.CLOSE))));
    }

    @Test
    void authoritativeLearnedSkillSnapshotSuppressesWandFallbackWhenAttackCacheIsStale() {
        Character bot = mock(Character.class);
        Skill energyBolt = mock(Skill.class);
        StatEffect effect = mock(StatEffect.class);
        when(energyBolt.getId()).thenReturn(2001004);
        when(energyBolt.getMaxLevel()).thenReturn(20);
        when(energyBolt.getEffect(1)).thenReturn(effect);
        when(effect.hasDamage()).thenReturn(true);
        when(effect.getMpCon()).thenReturn((short) 1);

        assertTrue(AgentCombatPlanRuntime.hasLearnedOffensiveMagicSkill(bot, Map.of(
                energyBolt, new Character.SkillEntry((byte) 1, 0, -1L))));
    }

    @Test
    void passiveMagicianSkillDoesNotSuppressBasicFallback() {
        Character bot = mock(Character.class);
        Skill passive = mock(Skill.class);
        StatEffect effect = mock(StatEffect.class);
        when(passive.getId()).thenReturn(2000000);
        when(passive.getMaxLevel()).thenReturn(16);
        when(passive.getEffect(5)).thenReturn(effect);

        assertFalse(AgentCombatPlanRuntime.hasLearnedOffensiveMagicSkill(bot, Map.of(
                passive, new Character.SkillEntry((byte) 5, 0, -1L))));
    }

    @Test
    void rangedWeaponUsesRangedPlanAgainstBossInsteadOfWeaponNeutralMeleeSkill() {
        Monster boss = mock(Monster.class);
        when(boss.isBoss()).thenReturn(true);
        AgentAttackPlan somersaultKick = plan(5001002, AgentAttackRoute.CLOSE);
        AgentAttackPlan doubleShot = plan(5001003, AgentAttackRoute.RANGED);

        assertEquals(List.of(doubleShot), AgentCombatPlanRuntime.preferRangedBossCandidates(
                boss, WeaponType.GUN, List.of(somersaultKick, doubleShot)));
        assertEquals(List.of(somersaultKick, doubleShot), AgentCombatPlanRuntime.preferRangedBossCandidates(
                boss, WeaponType.KNUCKLE, List.of(somersaultKick, doubleShot)));
    }

    /** 2026-10-27 (user): a shooter's basic attack is a projectile, and only while it is out of MP for its skills. */
    @Test
    void aBowAgentShootsItsBasicAttackOnlyWhenItCannotPayAnAttackSkill() {
        Character bot = mock(Character.class);
        Skill doubleShot = mock(Skill.class);
        StatEffect effect = mock(StatEffect.class);
        when(bot.getSkillLevel(doubleShot)).thenReturn((byte) 20);
        when(doubleShot.getEffect(20)).thenReturn(effect);

        try (MockedStatic<SkillFactory> skillFactory = mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(Archer.DOUBLE_SHOT)).thenReturn(doubleShot);

            when(effect.canPaySkillCost(bot)).thenReturn(true);
            assertFalse(AgentCombatPlanRuntime.basicShotAllowed(bot, WeaponType.BOW, List.of(Archer.DOUBLE_SHOT)));

            when(effect.canPaySkillCost(bot)).thenReturn(false);
            assertTrue(AgentCombatPlanRuntime.basicShotAllowed(bot, WeaponType.BOW, List.of(Archer.DOUBLE_SHOT)));
        }
        assertTrue(AgentCombatPlanRuntime.basicShotAllowed(bot, WeaponType.BOW, List.of()), "no attack skill yet");
    }

    /**
     * 2026-10-29: CWKPQ's Margana, Rellik and Hsalf and Pink Bean's Ariel are holy-immune (Mob.wz elemAttr H1). A Bishop
     * casts Big Bang (no element) at them, not Angel Ray (holy); with nothing else to cast it still casts, and against
     * a target its element hurts it chooses as before.
     */
    @Test
    void aSkillOfAnElementTheTargetIsImmuneToGivesWayToOneThatHurtsIt() {
        Monster margana = mock(Monster.class);
        Monster redNirg = mock(Monster.class);
        when(margana.getElementalEffectiveness(any())).thenReturn(ElementalEffectiveness.STRONG);
        when(margana.getElementalEffectiveness(Element.HOLY)).thenReturn(ElementalEffectiveness.IMMUNE);
        when(redNirg.getElementalEffectiveness(any())).thenReturn(ElementalEffectiveness.NORMAL);
        Skill angelRay = mock(Skill.class);
        Skill bigBang = mock(Skill.class);
        when(angelRay.getElement()).thenReturn(Element.HOLY);
        when(bigBang.getElement()).thenReturn(Element.NEUTRAL);
        AgentAttackPlan ray = plan(Bishop.ANGEL_RAY, AgentAttackRoute.MAGIC);
        AgentAttackPlan bang = plan(Bishop.BIG_BANG, AgentAttackRoute.MAGIC);

        try (MockedStatic<SkillFactory> skillFactory = mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(Bishop.ANGEL_RAY)).thenReturn(angelRay);
            skillFactory.when(() -> SkillFactory.getSkill(Bishop.BIG_BANG)).thenReturn(bigBang);

            assertEquals(List.of(bang), AgentCombatPlanRuntime.skipImmuneElements(margana, List.of(ray, bang)));
            assertEquals(List.of(ray), AgentCombatPlanRuntime.skipImmuneElements(margana, List.of(ray)));
            assertEquals(List.of(ray, bang), AgentCombatPlanRuntime.skipImmuneElements(redNirg, List.of(ray, bang)));
        }
    }

    @Test
    void aMeleeAgentKeepsItsBasicAttackBesideItsSkills() {
        Character bot = mock(Character.class);

        assertTrue(AgentCombatPlanRuntime.basicShotAllowed(bot, WeaponType.SWORD1H, List.of(Archer.DOUBLE_SHOT)));
    }

    private static AgentAttackPlan plan(int skillId, AgentAttackRoute route) {
        return new AgentAttackPlan(skillId, 1, 1, null, List.of(), route,
                0, 0, 0, 0, 0, 1, 1, WeaponType.WAND);
    }
}
