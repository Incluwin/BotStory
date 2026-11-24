package server.agents.capabilities.combat;

import client.BuffStat;
import client.Job;
import client.SkillFactory;
import client.inventory.WeaponType;
import constants.skills.Paladin;
import constants.skills.Warrior;
import constants.skills.WhiteKnight;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.life.Monster;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 2026-11-24: Charged Blow uses up the weapon's charge and the buff loop puts it back at once, so every blow cost two
 * casts while the scorer charged it for one: a Lv 100 White Knight on one target cast Fire Charge 57 times in 180 s.
 */
class AgentChargedBlowCostTest {
    private static final int TWO_HANDED_SWORD = 1_402_000;
    private static final int ANIMATION_MS = 720;

    @BeforeAll
    static void loadSkills() {
        SkillFactory.loadAllSkills();
    }

    @Test
    void onOneTargetAWhiteKnightKeepsItsChargeAndPowerStrikes() {
        assertEquals(Warrior.POWER_STRIKE, pick(knight(0), 1));
    }

    @Test
    void onAPackChargedBlowIsWorthItsRecast() {
        assertEquals(WhiteKnight.CHARGE_BLOW, pick(knight(0), 4));
    }

    @Test
    void advancedChargeThatKeepsTheChargeMakesChargedBlowFree() {
        assertEquals(WhiteKnight.CHARGE_BLOW, pick(knight(10), 1), "kept 100% of the time");
        assertEquals(Warrior.POWER_STRIKE, pick(knight(1), 1), "kept 10% of the time");
    }

    @Test
    void theCostIsTheChargesOwnCastTimesTheChanceItIsUsedUp() {
        int charge = WhiteKnight.SWORD_FIRE_CHARGE;
        assertEquals(1800, AgentWeaponChargePolicy.spentChargeMs(WhiteKnight.CHARGE_BLOW, charge, 0, id -> 1800));
        assertEquals(900, AgentWeaponChargePolicy.spentChargeMs(WhiteKnight.CHARGE_BLOW, charge, 50, id -> 1800));
        assertEquals(0, AgentWeaponChargePolicy.spentChargeMs(WhiteKnight.CHARGE_BLOW, charge, 100, id -> 1800));
        assertEquals(0, AgentWeaponChargePolicy.spentChargeMs(WhiteKnight.CHARGE_BLOW, -1, 0, id -> 1800), "no charge up");
        assertEquals(0, AgentWeaponChargePolicy.spentChargeMs(Warrior.POWER_STRIKE, charge, 0, id -> 1800));
    }

    private static AgentProcFixture knight(int advancedCharge) {
        AgentProcFixture knight = new AgentProcFixture(advancedCharge > 0 ? Job.PALADIN : Job.WHITEKNIGHT,
                TWO_HANDED_SWORD, WeaponType.SWORD2H)
                .skill(Warrior.POWER_STRIKE, 20)
                .skill(WhiteKnight.CHARGE_BLOW, 30)
                .skill(WhiteKnight.SWORD_FIRE_CHARGE, 1)
                .skill(Paladin.ADVANCED_CHARGE, advancedCharge)
                .buff(BuffStat.WK_CHARGE, 1);
        when(knight.bot.calculateMaxBaseDamage(anyInt(), any())).thenReturn(1_000);
        when(knight.bot.calculateMinBaseDamage(anyInt(), anyDouble(), any())).thenReturn(500);
        when(knight.bot.getBuffSource(BuffStat.WK_CHARGE)).thenReturn(WhiteKnight.SWORD_FIRE_CHARGE);
        return knight;
    }

    private static int pick(AgentProcFixture knight, int mobs) {
        List<Monster> pack = new ArrayList<>();
        for (int i = 0; i < mobs; i++) {
            pack.add(knight.monster(5_000 + i, 140));
        }
        List<AgentAttackPlan> plans = List.of(plan(Warrior.POWER_STRIKE, 20, pack.subList(0, 1)),
                plan(WhiteKnight.CHARGE_BLOW, 30, pack));
        InventoryGateway items = mock(InventoryGateway.class);
        when(items.getWeaponType(TWO_HANDED_SWORD)).thenReturn(WeaponType.SWORD2H);
        when(items.isTwoHandedWeapon(TWO_HANDED_SWORD)).thenReturn(true);
        try (MockedStatic<AgentInventoryGatewayRuntime> inventory = mockStatic(AgentInventoryGatewayRuntime.class)) {
            inventory.when(AgentInventoryGatewayRuntime::inventory).thenReturn(items);
            return knight.stubbed(() -> AgentAttackPlanScoringPolicy.selectBestAttackPlan(knight.bot, plans)).skillId;
        }
    }

    private static AgentAttackPlan plan(int skillId, int level, List<Monster> targets) {
        return new AgentAttackPlan(skillId, level, 1, new Rectangle(100, 150, 80, 70), targets, AgentAttackRoute.CLOSE,
                0, 5, 5, 0, 3, 372, ANIMATION_MS, WeaponType.SWORD2H);
    }
}
