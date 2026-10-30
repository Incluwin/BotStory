package server.agents.capabilities.combat;

import client.BuffStat;
import client.Job;
import client.SkillFactory;
import client.inventory.WeaponType;
import constants.skills.DawnWarrior;
import constants.skills.Fighter;
import constants.skills.Hunter;
import constants.skills.Page;
import constants.skills.Spearman;
import constants.skills.Warrior;
import net.server.channel.handlers.AbstractDealDamageHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.combat.data.AgentAttackTiming;
import server.life.Monster;

import java.awt.Rectangle;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-30: Agents never sent the Final Attack a real v83 client sends after Power Strike or Slash Blast. In monitored
 * packets of a Lv255 Fighter with a one-handed axe and Final Attack: Axe 30 (prop 60), 15 of 19 Power Strikes were
 * followed by a CLOSE_RANGE_ATTACK of their own with skill 1100003 (display 0, the axe's swingOF or stabOF, the Power
 * Strike's facing, one line on one monster or none), 480 ms after a swingO Power Strike and 420 ms after a stabO1
 * one; 30 basic attacks were followed by none. Skill.wz's finalAttack lists which skills set off which Final Attack
 * for which weapons.
 */
class AgentFinalAttackProcTest {
    private static final int ONE_HANDED_AXE = 1_312_004;
    private static final int TWO_HANDED_SWORD = 1_402_000;
    private static final int BOW = 1_452_002;
    private static final int POWER_STRIKE_LEVEL = 20;
    private static final int FINAL_ATTACK_LEVEL = 30;
    /** A swingO1 at attack speed 3 as the Agent times it: its hit at 372 ms, its end at 661 ms. */
    private static final int HIT_MS = 372;
    private static final int ANIMATION_MS = 661;

    @BeforeAll
    static void loadSkills() {
        SkillFactory.loadAllSkills();
    }

    @Test
    void aPowerStrikeIsFollowedByTheAxesFinalAttackOnceTheSwingIsDone() {
        AgentProcFixture fight = fighter(ONE_HANDED_AXE, WeaponType.GENERAL1H_SWING).chance(0.0);
        Monster struck = fight.monster(5_000, 140);

        assertTrue(fight.swing(powerStrike(struck)).committed());

        assertEquals(1, fight.sent.size(), "the Power Strike now");
        assertEquals(1, fight.scheduled.size(), "its Final Attack later");
        int startMs = HIT_MS + (int) Math.round(0.4 * (ANIMATION_MS - HIT_MS));
        assertEquals(startMs, fight.scheduled.get(0).delayMs());
        // swingOF and stabOF both last 700 ms at speed 0; the axe swings at its own speed, 5
        assertEquals(startMs + AgentAttackTiming.adjustDelayMillis(700, 5),
                AgentCombatCooldownStateRuntime.attackCooldownMs(fight.entry),
                "the Agent stays in the attack until the Final Attack's swing ends");

        fight.runScheduled();

        assertEquals(2, fight.sent.size());
        AgentProcFixture.Sent finalAttack = fight.sent.get(1);
        AbstractDealDamageHandler.AttackInfo sent = finalAttack.attack();
        assertEquals(AgentAttackRoute.CLOSE, finalAttack.route());
        assertEquals(Fighter.FINAL_ATTACK_AXE, sent.skill);
        assertEquals(FINAL_ATTACK_LEVEL, sent.skilllevel);
        assertEquals(0, sent.display);
        assertTrue(Set.of(8, 18).contains(sent.direction), "swingOF or stabOF, not " + sent.direction);
        assertEquals(0x00, sent.stance, "the Power Strike's facing (right)");
        assertEquals(3, sent.speed);
        assertEquals(0x11, sent.numAttackedAndDamage, "one monster, one line");
        assertEquals(Set.of(5_000), sent.targets.keySet());
    }

    @Test
    void aFailedRollSendsNothingAndKeepsTheSwingsOwnLock() {
        AgentProcFixture fight = fighter(ONE_HANDED_AXE, WeaponType.GENERAL1H_SWING).chance(0.6);
        Monster struck = fight.monster(5_000, 140);

        assertTrue(fight.swing(powerStrike(struck)).committed());

        assertEquals(List.of(), fight.scheduled, "prop 60: a roll of 0.6 misses");
        assertEquals(ANIMATION_MS, AgentCombatCooldownStateRuntime.attackCooldownMs(fight.entry));
    }

    @Test
    void withNothingInReachTheFinalAttackStillSwings() {
        AgentProcFixture fight = fighter(ONE_HANDED_AXE, WeaponType.GENERAL1H_SWING).chance(0.0);
        Monster struck = fight.monster(5_000, 140);
        assertTrue(fight.swing(powerStrike(struck)).committed());
        fight.monsters.clear(); // the Power Strike killed it

        fight.runScheduled();

        assertEquals(2, fight.sent.size(), "5 of the 15 captured Final Attacks listed no monster");
        assertEquals(0x01, fight.sent.get(1).attack().numAttackedAndDamage);
    }

    @Test
    void onlyASkillThatListsTheFinalAttackForTheHeldWeaponSetsItOff() {
        AgentProcFixture axe = fighter(ONE_HANDED_AXE, WeaponType.GENERAL1H_SWING);
        Monster struck = axe.monster(5_000, 140);
        assertNotNull(roll(axe, powerStrike(struck), 0.0));
        assertNull(roll(axe, basicSwing(struck), 0.0), "0 of 30 basic attacks");
        assertNull(roll(axe, powerStrike(struck), 0.6));

        AgentProcFixture twoHandedSword = fighter(TWO_HANDED_SWORD, WeaponType.SWORD2H);
        assertNull(roll(twoHandedSword, powerStrike(struck), 0.0),
                "Final Attack: Axe is for axes; this Fighter has no Final Attack: Sword");

        AgentProcFixture untrained = new AgentProcFixture(Job.FIGHTER, ONE_HANDED_AXE, WeaponType.GENERAL1H_SWING)
                .skill(Warrior.POWER_STRIKE, POWER_STRIKE_LEVEL);
        assertNull(roll(untrained, powerStrike(struck), 0.0), "no Final Attack learned");
    }

    @Test
    void aBowDrawsNoFinalSwingSoAHuntersFinalAttackIsNotSent() {
        AgentProcFixture hunter = new AgentProcFixture(Job.HUNTER, BOW, WeaponType.BOW)
                .skill(3_001_004, 20).skill(Hunter.FINAL_ATTACK, 30);
        Monster struck = hunter.monster(5_000, 400);

        assertNull(roll(hunter, new AgentAttackPlan(3_001_004, 20, 1, null,
                List.of(struck), AgentAttackRoute.RANGED, 0, 22, 22, 0, 3, 300, 600, null), 0.0));
    }

    @Test
    void aDawnWarriorsFinalAttackNeedsItsBuff() {
        AgentProcFixture dawnWarrior = new AgentProcFixture(Job.DAWNWARRIOR2, 1_302_000, WeaponType.SWORD1H)
                .skill(11_001_002, 20).skill(DawnWarrior.FINAL_ATTACK, 20);
        Monster struck = dawnWarrior.monster(5_000, 140);
        AgentAttackPlan powerStrike = new AgentAttackPlan(11_001_002, 20, 1, new Rectangle(100, 150, 80, 70),
                List.of(struck), AgentAttackRoute.CLOSE, 0, 5, 5, 0, 3, HIT_MS, ANIMATION_MS, WeaponType.SWORD1H);

        assertNull(roll(dawnWarrior, powerStrike, 0.0));
        dawnWarrior.buff(BuffStat.FINALATTACK, 1);
        assertNotNull(roll(dawnWarrior, powerStrike, 0.0));
    }

    private static AgentFinalAttackProc.Rolled roll(AgentProcFixture fixture, AgentAttackPlan swing, double chance) {
        return fixture.stubbed(() -> AgentFinalAttackProc.roll(fixture.bot, swing, chance));
    }

    @Test
    void skillWzListsTheFinalAttacksPowerStrikeSetsOffAndTheirWeapons() {
        assertEquals(Map.of(Fighter.FINAL_ATTACK_SWORD, Set.of(30, 40), Fighter.FINAL_ATTACK_AXE, Set.of(31, 41),
                        Page.FINAL_ATTACK_SWORD, Set.of(30, 40), Page.FINAL_ATTACK_BW, Set.of(32, 42),
                        Spearman.FINAL_ATTACK_SPEAR, Set.of(43), Spearman.FINAL_ATTACK_POLEARM, Set.of(44)),
                SkillFactory.getSkill(Warrior.POWER_STRIKE).getFinalAttacks());
        assertEquals(Map.of(), SkillFactory.getSkill(1_121_008).getFinalAttacks(), "Brandish lists none");
    }

    @Test
    void theFinalAttackStartsWhereTheCapturedOnesDid() {
        // the client's own timing at speed 3: swingO1 hits at 364 ms and ends at 650, stabO1 hits at 283
        assertEquals(478, AgentFinalAttackProc.startMs(364, 650), "captured 480");
        assertEquals(430, AgentFinalAttackProc.startMs(283, 650), "captured 420");
    }

    @Test
    void aFinalSwingIsTheFinalFormOfABasicOne() {
        assertEquals("swingOF", AgentAttackExecutionProvider.finalFormOf("swingO2"));
        assertEquals("stabOF", AgentAttackExecutionProvider.finalFormOf("stabO1"));
        assertEquals("swingTF", AgentAttackExecutionProvider.finalFormOf("swingT3"));
        assertEquals("swingPF", AgentAttackExecutionProvider.finalFormOf("swingP1"));
        assertEquals("stabTF", AgentAttackExecutionProvider.finalFormOf("stabT1"));
        assertNull(AgentAttackExecutionProvider.finalFormOf("shoot1"));
        assertNull(AgentAttackExecutionProvider.finalFormOf("swingOF"));
    }

    private static AgentProcFixture fighter(int weaponItemId, WeaponType weaponType) {
        return new AgentProcFixture(Job.FIGHTER, weaponItemId, weaponType)
                .skill(Warrior.POWER_STRIKE, POWER_STRIKE_LEVEL)
                .skill(Fighter.FINAL_ATTACK_AXE, FINAL_ATTACK_LEVEL);
    }

    /** A Power Strike (swingO1) facing right at speed 3, at a snail 40 px ahead. */
    private static AgentAttackPlan powerStrike(Monster target) {
        return new AgentAttackPlan(Warrior.POWER_STRIKE, POWER_STRIKE_LEVEL, 1, new Rectangle(100, 150, 80, 70),
                List.of(target), AgentAttackRoute.CLOSE, 0, 5, 5, AgentAttackExecutionProvider.attackPacketStance(false),
                3, HIT_MS, ANIMATION_MS, WeaponType.GENERAL1H_SWING);
    }

    private static AgentAttackPlan basicSwing(Monster target) {
        return new AgentAttackPlan(0, 0, 1, new Rectangle(100, 150, 80, 70), List.of(target), AgentAttackRoute.CLOSE,
                0, 5, 5, 0, 3, HIT_MS, ANIMATION_MS, WeaponType.GENERAL1H_SWING);
    }
}
