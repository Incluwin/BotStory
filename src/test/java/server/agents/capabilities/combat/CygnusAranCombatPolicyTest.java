package server.agents.capabilities.combat;

import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.WeaponType;
import constants.skills.Aran;
import constants.skills.BlazeWizard;
import constants.skills.DawnWarrior;
import constants.skills.NightWalker;
import constants.skills.ThunderBreaker;
import constants.skills.WindArcher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.combat.AgentCombatSkillClassifier.SkillCacheBucket;
import server.agents.capabilities.equipment.AgentWeaponCompatibilityPolicy;
import server.agents.integration.CombatGateway;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The combat tables a Cygnus Knight or Aran goes through, over real Skill.wz data: which bucket the skill cache puts
 * each skill in (only what lands in a bucket is ever used), the weapon each job holds and each skill needs, the
 * Aran's combo counter and Final Charge's dash.
 */
class CygnusAranCombatPolicyTest {

    @BeforeAll
    static void loadSkills() {
        SkillFactory.loadAllSkills();
    }

    @Test
    void everyKnightAndAranSkillLandsInTheBucketItsExplorerCounterpartDoes() {
        Map<SkillCacheBucket, List<Integer>> expected = Map.of(
                SkillCacheBucket.ACTIVE_ATTACK, List.of(DawnWarrior.POWER_STRIKE, DawnWarrior.SLASH_BLAST,
                        DawnWarrior.SOUL_BLADE, DawnWarrior.PANIC, DawnWarrior.COMA, DawnWarrior.BRANDISH,
                        DawnWarrior.SOUL_DRIVER, BlazeWizard.MAGIC_CLAW, BlazeWizard.FIRE_ARROW, BlazeWizard.FIRE_PILLAR,
                        BlazeWizard.FIRE_STRIKE, BlazeWizard.FLAME_GEAR, BlazeWizard.METEOR_SHOWER,
                        WindArcher.DOUBLE_SHOT, WindArcher.STORM_BREAK, WindArcher.ARROW_RAIN, WindArcher.STRAFE,
                        WindArcher.HURRICANE, WindArcher.WIND_PIERCING, WindArcher.WIND_SHOT, NightWalker.LUCKY_SEVEN,
                        NightWalker.DISORDER, NightWalker.VAMPIRE, NightWalker.SHADOW_WEB, NightWalker.AVENGER,
                        NightWalker.TRIPLE_THROW, NightWalker.POISON_BOMB, ThunderBreaker.FIRST_STRIKE,
                        ThunderBreaker.SOMERSAULT_KICK, ThunderBreaker.CORKSCREW_BLOW, ThunderBreaker.ENERGY_BLAST,
                        ThunderBreaker.ENERGY_DRAIN, ThunderBreaker.SHOCK_WAVE, ThunderBreaker.BARRAGE,
                        ThunderBreaker.SHARK_WAVE, Aran.DOUBLE_SWING, Aran.TRIPLE_SWING, Aran.FINAL_CHARGE,
                        Aran.COMBO_SMASH, Aran.FINAL_TOSS, Aran.COMBO_FENRIR, Aran.ROLLING_SPIN, Aran.HIDDEN_FULL_DOUBLE,
                        Aran.HIDDEN_FULL_TRIPLE, Aran.FINAL_BLOW, Aran.COMBO_TEMPEST, Aran.HIDDEN_OVER_DOUBLE,
                        Aran.HIDDEN_OVER_TRIPLE),
                SkillCacheBucket.SUPPORT_BUFF, List.of(DawnWarrior.IRON_BODY, DawnWarrior.SWORD_BOOSTER,
                        DawnWarrior.RAGE, DawnWarrior.COMBO, DawnWarrior.SOUL_CHARGE, BlazeWizard.MAGIC_GUARD,
                        BlazeWizard.MAGIC_ARMOR, BlazeWizard.MEDITATION, BlazeWizard.SPELL_BOOSTER,
                        BlazeWizard.ELEMENTAL_RESET, WindArcher.FOCUS, WindArcher.BOW_BOOSTER, WindArcher.SOUL_ARROW,
                        WindArcher.EAGLE_EYE, NightWalker.CLAW_BOOSTER, NightWalker.HASTE, NightWalker.SHADOW_PARTNER,
                        ThunderBreaker.KNUCKLER_BOOSTER, ThunderBreaker.LIGHTNING_CHARGE, ThunderBreaker.TRANSFORMATION,
                        ThunderBreaker.SPEED_INFUSION, ThunderBreaker.SPARK, Aran.POLEARM_BOOSTER, Aran.COMBO_DRAIN,
                        Aran.BODY_PRESSURE, Aran.SMART_KNOCKBACK, Aran.SNOW_CHARGE, Aran.MAPLE_WARRIOR,
                        Aran.FREEZE_STANDING, Aran.COMBO_BARRIER),
                SkillCacheBucket.SUMMON, List.of(DawnWarrior.SOUL, BlazeWizard.FLAME, BlazeWizard.IFRIT,
                        WindArcher.STORM, WindArcher.PUPPET, NightWalker.DARKNESS, ThunderBreaker.LIGHTNING),
                SkillCacheBucket.DEBUFF, List.of(BlazeWizard.SLOW, BlazeWizard.SEAL));
        expected.forEach((bucket, skillIds) -> {
            for (int skillId : skillIds) {
                Skill skill = SkillFactory.getSkill(skillId);
                assertEquals(bucket, AgentCombatSkillClassifier.classifySkillCacheBucket(skill,
                        skill.getEffect(skill.getMaxLevel())), SkillFactory.getSkillName(skillId) + " (" + skillId + ")");
            }
        });
    }

    @Test
    void eachKnightAndAranJobHoldsItsLinesOneWeapon() {
        assertTrue(holds(Job.DAWNWARRIOR3, WeaponType.SWORD2H));
        assertFalse(holds(Job.DAWNWARRIOR3, WeaponType.GENERAL1H_SWING), "no axe: Sword Mastery is a Dawn Warrior's");
        assertTrue(holds(Job.BLAZEWIZARD2, WeaponType.STAFF));
        assertFalse(holds(Job.BLAZEWIZARD2, WeaponType.SWORD1H));
        assertTrue(holds(Job.WINDARCHER1, WeaponType.BOW));
        assertFalse(holds(Job.WINDARCHER1, WeaponType.CROSSBOW), "a Wind Archer has no crossbow skills");
        assertTrue(holds(Job.NIGHTWALKER3, WeaponType.CLAW));
        assertFalse(holds(Job.NIGHTWALKER3, WeaponType.DAGGER_THIEVES));
        assertTrue(holds(Job.THUNDERBREAKER2, WeaponType.KNUCKLE));
        assertFalse(holds(Job.THUNDERBREAKER2, WeaponType.GUN));
        assertTrue(holds(Job.ARAN4, WeaponType.POLE_ARM_STAB));
        assertFalse(holds(Job.ARAN4, WeaponType.SPEAR_STAB), "an Aran's skills are polearm skills");
        assertTrue(holds(Job.NOBLESSE, WeaponType.SWORD1H) && holds(Job.LEGEND, WeaponType.BOW), "a beginner holds any");

        assertTrue(AgentWeaponCompatibilityPolicy.isMageJob(Job.BLAZEWIZARD3), "accuracy means nothing to its magic");
    }

    @Test
    void weaponBoundSkillsNeedTheirWeapon() {
        assertFalse(AgentCombatWeaponPolicy.canUseSkillWithWeapon(NightWalker.LUCKY_SEVEN, WeaponType.BOW));
        assertTrue(AgentCombatWeaponPolicy.canUseSkillWithWeapon(NightWalker.LUCKY_SEVEN, WeaponType.CLAW));
        assertFalse(AgentCombatWeaponPolicy.canUseSkillWithWeapon(WindArcher.HURRICANE, WeaponType.CLAW));
        assertFalse(AgentCombatWeaponPolicy.canUseSkillWithWeapon(ThunderBreaker.SHARK_WAVE, WeaponType.GUN));
        assertFalse(AgentCombatWeaponPolicy.canUseSkillWithWeapon(DawnWarrior.SOUL_CHARGE, WeaponType.GENERAL2H_SWING));
        assertFalse(AgentCombatWeaponPolicy.canUseSkillWithWeapon(Aran.COMBO_SMASH, WeaponType.SPEAR_SWING));
        assertTrue(AgentCombatWeaponPolicy.canUseSkillWithWeapon(Aran.COMBO_SMASH, WeaponType.POLE_ARM_SWING));
    }

    @Test
    void anAranCountsOneComboPerMonsterItHitAndNoneWhenItSpendsIt() {
        Character aran = mock(Character.class);
        when(aran.getJob()).thenReturn(Job.ARAN2);
        CombatGateway combat = mock(CombatGateway.class);
        when(combat.dispatchAranComboCount(aran)).thenReturn(true);

        assertEquals(3, AgentAranCombo.countHits(aran, Aran.TRIPLE_SWING, 3, combat));
        verify(combat, times(3)).dispatchAranComboCount(aran);
        assertEquals(0, AgentAranCombo.countHits(aran, Aran.COMBO_SMASH, 6, combat), "Combo Smash spends the combo");
        assertEquals(0, AgentAranCombo.countHits(aran, Aran.TRIPLE_SWING, 0, combat), "a miss counts nothing");
        verify(combat, times(3)).dispatchAranComboCount(aran);

        Character knight = mock(Character.class);
        when(knight.getJob()).thenReturn(Job.DAWNWARRIOR2);
        assertEquals(0, AgentAranCombo.countHits(knight, DawnWarrior.SOUL_BLADE, 4, combat));
        verify(combat, never()).dispatchAranComboCount(knight);
    }

    @Test
    void theComboTheClientShowsDropsToZeroThreeSecondsAfterTheLastHit() {
        assertEquals(42, AgentAranCombo.current(42, 10_000, 13_000));
        assertEquals(0, AgentAranCombo.current(42, 10_000, 13_001));
        assertEquals(30, AgentAranCombo.required(Aran.COMBO_SMASH));
        assertEquals(100, AgentAranCombo.required(Aran.COMBO_FENRIR));
        assertEquals(200, AgentAranCombo.required(Aran.COMBO_TEMPEST));
        assertEquals(0, AgentAranCombo.required(Aran.FINAL_CHARGE));
    }

    @Test
    void finalChargeCarriesTheAranForwardThroughItsBox() {
        Skill finalCharge = SkillFactory.getSkill(Aran.FINAL_CHARGE);

        assertTrue(AgentCombatDashPolicy.dashDistancePx(Aran.FINAL_CHARGE,
                finalCharge.getEffect(finalCharge.getMaxLevel())) > 0);
    }

    private static boolean holds(Job job, WeaponType weapon) {
        return AgentWeaponCompatibilityPolicy.isWeaponCompatible(job, id -> 0, weapon);
    }
}
