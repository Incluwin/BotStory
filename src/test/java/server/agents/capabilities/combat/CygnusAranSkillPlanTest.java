package server.agents.capabilities.combat;

import client.BuffStat;
import client.Character;
import client.Disease;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import constants.skills.Aran;
import constants.skills.BlazeWizard;
import constants.skills.DawnWarrior;
import constants.skills.NightWalker;
import constants.skills.ThunderBreaker;
import constants.skills.WindArcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentCombatGatewayRuntime;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.CombatGateway;
import server.agents.integration.InventoryGateway;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Each Cygnus Knight's and the Aran's attack skills through the real planner (AgentSkillAttackPlanRuntime: its gates,
 * Skill.wz data, hit box and packet route) with the line's own weapon and a Snail in front of it: the offline
 * counterpart of a !skillprobe run, which needs a running server. Every skill plans, on the route the v83 client sends
 * it by (close range 0xBA, ranged 0xBB, magic 0xBC); a skill with a resource gate plans only with the resource.
 */
class CygnusAranSkillPlanTest {
    private static final int SNAIL = 100_100;
    private static final int SWORD = 1_302_000;
    private static final int WAND = 1_372_005;
    private static final int BOW = 1_452_002;
    private static final int CLAW = 1_472_000;
    private static final int KNUCKLE = 1_482_000;
    private static final int POLEARM = 1_442_000;
    private static final int ARROWS = 2_060_000;
    private static final int STARS = 2_070_000;
    private static final long NOW = 1_000_000L;

    private MockedStatic<AgentInventoryGatewayRuntime> inventory;
    private MockedStatic<AgentCombatGatewayRuntime> combat;

    @BeforeAll
    static void loadSkills() {
        SkillFactory.loadAllSkills();
    }

    @BeforeEach
    void stubGateways() {
        InventoryGateway items = mock(InventoryGateway.class);
        when(items.getWeaponType(SWORD)).thenReturn(WeaponType.SWORD1H);
        when(items.getWeaponType(WAND)).thenReturn(WeaponType.WAND);
        when(items.getWeaponType(BOW)).thenReturn(WeaponType.BOW);
        when(items.getWeaponType(CLAW)).thenReturn(WeaponType.CLAW);
        when(items.getWeaponType(KNUCKLE)).thenReturn(WeaponType.KNUCKLE);
        when(items.getWeaponType(POLEARM)).thenReturn(WeaponType.POLE_ARM_SWING);
        when(items.isTwoHandedWeapon(POLEARM)).thenReturn(true);
        inventory = mockStatic(AgentInventoryGatewayRuntime.class);
        inventory.when(AgentInventoryGatewayRuntime::inventory).thenReturn(items);
        CombatGateway gateway = mock(CombatGateway.class);
        when(gateway.serverTimeMs()).thenReturn(NOW);
        combat = mockStatic(AgentCombatGatewayRuntime.class);
        combat.when(AgentCombatGatewayRuntime::combat).thenReturn(gateway);
    }

    @AfterEach
    void closeGateways() {
        inventory.close();
        combat.close();
    }

    @Test
    void aDawnWarriorSwingsEachOfItsAttacksClose() {
        assertPlans(Job.DAWNWARRIOR3, SWORD, 0, 60, AgentAttackRoute.CLOSE, List.of(DawnWarrior.POWER_STRIKE,
                DawnWarrior.SLASH_BLAST, DawnWarrior.SOUL_BLADE, DawnWarrior.BRANDISH, DawnWarrior.SOUL_DRIVER));
    }

    @Test
    void aBlazeWizardCastsEachOfItsAttacksAsMagic() {
        assertPlans(Job.BLAZEWIZARD3, WAND, 0, 120, AgentAttackRoute.MAGIC, List.of(BlazeWizard.MAGIC_CLAW,
                BlazeWizard.FIRE_ARROW, BlazeWizard.FIRE_PILLAR, BlazeWizard.FIRE_STRIKE, BlazeWizard.FLAME_GEAR));
    }

    @Test
    void aWindArcherShootsEachOfItsAttacksRanged() {
        assertPlans(Job.WINDARCHER3, BOW, ARROWS, 250, AgentAttackRoute.RANGED, List.of(WindArcher.DOUBLE_SHOT,
                WindArcher.STRAFE, WindArcher.HURRICANE,
                WindArcher.WIND_PIERCING, WindArcher.WIND_SHOT));
        // Storm Break's box (Skill.wz lt -230,-90) ends 230 px ahead; Arrow Rain falls 170 px around the archer
        assertPlans(Job.WINDARCHER3, BOW, ARROWS, 200, AgentAttackRoute.RANGED, List.of(WindArcher.STORM_BREAK));
        assertPlans(Job.WINDARCHER3, BOW, ARROWS, 150, AgentAttackRoute.RANGED, List.of(WindArcher.ARROW_RAIN));
    }

    @Test
    void aNightWalkerThrowsEachOfItsAttacksRanged() {
        assertPlans(Job.NIGHTWALKER3, CLAW, STARS, 250, AgentAttackRoute.RANGED, List.of(NightWalker.LUCKY_SEVEN,
                NightWalker.VAMPIRE, NightWalker.AVENGER, NightWalker.TRIPLE_THROW));
        // Poison Bomb's blast (lt -100,-82 rb 100,83) is laid out around the thrower
        assertPlans(Job.NIGHTWALKER3, CLAW, STARS, 90, AgentAttackRoute.RANGED, List.of(NightWalker.POISON_BOMB));
    }

    @Test
    void aThunderBreakerPunchesCloseAndSendsSharkWaveRanged() {
        assertPlans(Job.THUNDERBREAKER3, KNUCKLE, 0, 60, AgentAttackRoute.CLOSE, List.of(ThunderBreaker.FIRST_STRIKE,
                ThunderBreaker.SOMERSAULT_KICK, ThunderBreaker.CORKSCREW_BLOW, ThunderBreaker.SHOCK_WAVE,
                ThunderBreaker.BARRAGE));
        assertPlans(Job.THUNDERBREAKER3, KNUCKLE, 0, 120, AgentAttackRoute.RANGED, List.of(ThunderBreaker.SHARK_WAVE));
    }

    @Test
    void aThunderBreakersEnergyAttacksWaitForAFullBar() {
        Monster snail = snail(new Point(60, 0));
        Character empty = agent(Job.THUNDERBREAKER3, KNUCKLE, 0, List.of(ThunderBreaker.ENERGY_BLAST), snail);
        Character full = agent(Job.THUNDERBREAKER3, KNUCKLE, 0, List.of(ThunderBreaker.ENERGY_BLAST), snail);
        when(full.getEnergyBar()).thenReturn(15_000);

        assertNull(plan(empty, snail, ThunderBreaker.ENERGY_BLAST), "energy-charge-not-full");
        assertNotNull(plan(full, snail, ThunderBreaker.ENERGY_BLAST), lastRejection());
    }

    @Test
    void anAranSwingsEachOfItsAttacksClose() {
        assertPlans(Job.ARAN4, POLEARM, 0, 60, AgentAttackRoute.CLOSE, List.of(Aran.DOUBLE_SWING, Aran.TRIPLE_SWING,
                Aran.FINAL_CHARGE, Aran.FINAL_TOSS, Aran.ROLLING_SPIN, Aran.FINAL_BLOW, Aran.HIDDEN_FULL_DOUBLE,
                Aran.HIDDEN_FULL_TRIPLE, Aran.HIDDEN_OVER_DOUBLE, Aran.HIDDEN_OVER_TRIPLE));
    }

    @Test
    void anAransComboSkillsPlanOnlyWithTheComboTheClientShows() {
        Monster snail = snail(new Point(60, 0));
        for (Map.Entry<Integer, Integer> skill : Map.of(Aran.COMBO_SMASH, 30, Aran.COMBO_FENRIR, 100,
                Aran.COMBO_TEMPEST, 200).entrySet()) {
            Character aran = agent(Job.ARAN4, POLEARM, 0, List.of(skill.getKey()), snail);
            when(aran.getLastCombo()).thenReturn(NOW - 500);

            when(aran.getCombo()).thenReturn((short) (skill.getValue() - 1));
            assertNull(plan(aran, snail, skill.getKey()), "one short of " + skill.getValue());

            when(aran.getCombo()).thenReturn((short) (int) skill.getValue());
            AgentAttackPlan smash = plan(aran, snail, skill.getKey());
            assertNotNull(smash, skill.getKey() + ": " + lastRejection());
            assertEquals(AgentAttackRoute.RANGED, smash.route, "RangedAttackHandler takes the combo skills");

            when(aran.getLastCombo()).thenReturn(NOW - 3_500);
            assertNull(plan(aran, snail, skill.getKey()), "a count over 3 s old: the client shows 0");
        }
    }

    @Test
    void anAransAttacksNeedItsPolearm() {
        Monster snail = snail(new Point(60, 0));
        Character swordAran = agent(Job.ARAN2, SWORD, 0, List.of(Aran.TRIPLE_SWING), snail);

        assertNull(plan(swordAran, snail, Aran.TRIPLE_SWING));
    }

    private void assertPlans(Job job, int weapon, int ammo, int mobOffsetX, AgentAttackRoute route, List<Integer> skills) {
        for (int skillId : skills) {
            Monster snail = snail(new Point(mobOffsetX, 0));
            Character bot = agent(job, weapon, ammo, List.of(skillId), snail);

            AgentAttackPlan plan = plan(bot, snail, skillId);

            assertNotNull(plan, job + " " + SkillFactory.getSkillName(skillId) + " (" + skillId + "): " + lastRejection());
            assertEquals(route, plan.route, SkillFactory.getSkillName(skillId) + "'s packet family");
        }
    }

    private static AgentAttackPlan plan(Character bot, Monster target, int skillId) {
        return AgentSkillAttackPlanRuntime.planSkillAttack(bot, target, skillId, AgentCombatConfig.cfg);
    }

    private static String lastRejection() {
        return String.valueOf(AgentSkillAttackPlanRuntime.lastRejection());
    }

    private static Monster snail(Point at) {
        Monster monster = mock(Monster.class);
        MonsterStats stats = mock(MonsterStats.class);
        MapleMap map = mock(MapleMap.class);
        when(monster.getId()).thenReturn(SNAIL);
        when(monster.getObjectId()).thenReturn(1);
        when(monster.isAlive()).thenReturn(true);
        when(monster.getPosition()).thenReturn(at);
        when(monster.getStats()).thenReturn(stats);
        when(monster.getMap()).thenReturn(map);
        when(map.getAllMonsters()).thenReturn(List.of(monster));
        return monster;
    }

    /** A max-level Agent of {@code job} at the origin holding {@code weapon}, its ammo, and {@code skills} at max level. */
    private static Character agent(Job job, int weapon, int ammo, List<Integer> skills, Monster target) {
        Character agent = mock(Character.class);
        Inventory equipped = mock(Inventory.class);
        when(equipped.getItem((short) -11)).thenReturn(new Item(weapon, (short) -11, (short) 1));
        when(agent.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        Inventory use = mock(Inventory.class);
        when(use.list()).thenReturn(ammo > 0 ? List.of(new Item(ammo, (short) 1, (short) 1_000)) : List.of());
        when(agent.getInventory(InventoryType.USE)).thenReturn(use);
        when(agent.getPosition()).thenReturn(new Point(0, 0));
        MapleMap map = target.getMap();
        when(agent.getMap()).thenReturn(map);
        when(agent.getJob()).thenReturn(job);
        when(agent.isCygnus()).thenReturn(job.getId() / 1000 == 1);
        when(agent.getLevel()).thenReturn(job.getId() / 1000 == 1 ? 120 : 200);
        when(agent.getHp()).thenReturn(19_000);
        when(agent.getMp()).thenReturn(30_000);
        when(agent.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        when(agent.hasDisease(any(Disease.class))).thenReturn(false);
        when(agent.getSkillLevel(any(Skill.class))).thenAnswer(invocation -> {
            Skill skill = invocation.getArgument(0);
            return (byte) (skills.contains(skill.getId()) ? skill.getMaxLevel() : 0);
        });
        when(agent.getSkillLevel(anyInt())).thenAnswer(invocation -> {
            int id = invocation.getArgument(0);
            Skill skill = SkillFactory.getSkill(id);
            return skill != null && skills.contains(id) ? skill.getMaxLevel() : 0;
        });
        return agent;
    }
}
