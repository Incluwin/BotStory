package server.agents.capabilities.combat;

import client.Character;
import client.Disease;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.WeaponType;
import constants.skills.Pirate;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.integration.CombatAttackApplicationResult;
import server.life.Monster;
import server.maps.MapleMap;

import java.util.List;
import java.awt.Point;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCombatAttackRuntimeTest {
    @Test
    void sharedHandlerResultIsTheAuthoritativeApplicationSignal() {
        assertTrue(CombatAttackApplicationResult.appliedResult().applied());
        assertFalse(CombatAttackApplicationResult.rejected(
                CombatAttackApplicationResult.Reason.HANDLER_REJECTED).applied());
    }

    @Test
    void deadPrimaryTargetIsRejectedAtFinalSendGate() {
        Character agent = mock(Character.class);
        Monster target = mock(Monster.class);
        when(target.isAlive()).thenReturn(false);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentAttackPlan plan = new AgentAttackPlan(
                0, 0, 1, null, List.of(target), AgentAttackRoute.CLOSE,
                0, 0, 0, 0, 4, 0, 600, null);

        AgentAttackTransactionResult result = AgentCombatAttackRuntime.attackMonster(entry, agent, plan);

        assertEquals(AgentAttackTransactionResult.Status.REJECTED, result.status());
        assertEquals(AgentAttackTransactionResult.Reason.TARGET_UNAVAILABLE, result.reason());
        assertFalse(AgentCombatCooldownStateRuntime.hasAttackCooldown(entry));
        verify(target).isAlive();
        verify(agent, never()).getMap();
    }

    @Test
    void targetFromAnotherMapIsRejectedByAuthoritativeGate() {
        Character agent = mock(Character.class);
        MapleMap agentMap = mock(MapleMap.class);
        MapleMap targetMap = mock(MapleMap.class);
        Monster target = mock(Monster.class);
        Inventory equipped = mock(Inventory.class);
        when(agent.getMap()).thenReturn(agentMap);
        when(agent.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        when(agentMap.hasTransitioningPlayerObserver()).thenReturn(false);
        when(agentMap.isMobPhysicsObserverWarmupComplete()).thenReturn(true);
        when(target.isAlive()).thenReturn(true);
        when(target.getMap()).thenReturn(targetMap);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentAttackPlan plan = new AgentAttackPlan(
                0, 0, 1, null, List.of(target), AgentAttackRoute.CLOSE,
                0, 0, 0, 0, 4, 0, 600, null);

        AgentAttackTransactionResult result = AgentCombatAttackRuntime.attackMonster(entry, agent, plan);

        assertEquals(AgentAttackTransactionResult.Status.REJECTED, result.status());
        assertEquals(AgentAttackTransactionResult.Reason.TARGET_NOT_IN_AGENT_MAP, result.reason());
        assertFalse(AgentCombatCooldownStateRuntime.hasAttackCooldown(entry));
    }

    @Test
    void finalSendGateRejectsAStaleAttackAfterTheAgentLeavesRange() {
        Character agent = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        Monster target = mock(Monster.class);
        Inventory equipped = mock(Inventory.class);
        when(agent.getMap()).thenReturn(map);
        when(agent.getMapId()).thenReturn(105100400);
        when(agent.getPosition()).thenReturn(new Point(0, 0));
        when(agent.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        when(target.isAlive()).thenReturn(true);
        when(target.getMap()).thenReturn(map);
        when(target.getPosition()).thenReturn(new Point(500, 0));
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentAttackPlan stalePlan = new AgentAttackPlan(
                0, 0, 1, new Rectangle(0, -50, 80, 70), List.of(target),
                AgentAttackRoute.CLOSE, 0, 0, 0, 0, 4, 0, 600, null);

        AgentAttackTransactionResult result = AgentCombatAttackRuntime.attackMonster(
                entry, agent, stalePlan);

        assertEquals(AgentAttackTransactionResult.Status.REJECTED, result.status());
        assertEquals(AgentAttackTransactionResult.Reason.TARGET_OUT_OF_RANGE, result.reason());
    }

    @Test
    void equipmentSwapRejectsAStaleWeaponIncompatibleSkillPlan() {
        Character agent = mock(Character.class);
        Monster target = mock(Monster.class);
        when(target.isAlive()).thenReturn(true);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentAttackPlan staleFlashFistPlan = new AgentAttackPlan(
                Pirate.FLASH_FIST, 1, 1, null, List.of(target), AgentAttackRoute.CLOSE,
                0, 0, 0, 0, 4, 0, 600, null);

        try (MockedStatic<AgentAttackExecutionProvider> execution = Mockito.mockStatic(
                AgentAttackExecutionProvider.class, Mockito.CALLS_REAL_METHODS)) {
            execution.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(agent))
                    .thenReturn(WeaponType.GUN);

            AgentAttackTransactionResult result = AgentCombatAttackRuntime.attackMonster(
                    entry, agent, staleFlashFistPlan);

            assertEquals(AgentAttackTransactionResult.Status.DEFERRED, result.status());
            assertEquals(AgentAttackTransactionResult.Reason.CANNOT_USE_SKILL, result.reason());
            verify(agent, never()).getMap();
        }
    }

    @Test
    void sealRejectsAPreviouslyPlannedSkillBeforeExecution() {
        Character agent = mock(Character.class);
        Monster target = mock(Monster.class);
        when(target.isAlive()).thenReturn(true);
        when(agent.hasDisease(Disease.SEAL)).thenReturn(true);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentAttackPlan skillPlan = new AgentAttackPlan(
                Pirate.FLASH_FIST, 1, 1, null, List.of(target), AgentAttackRoute.CLOSE,
                0, 0, 0, 0, 4, 0, 600, null);

        try (MockedStatic<AgentAttackExecutionProvider> execution = Mockito.mockStatic(
                AgentAttackExecutionProvider.class, Mockito.CALLS_REAL_METHODS)) {
            execution.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(agent))
                    .thenReturn(WeaponType.KNUCKLE);

            AgentAttackTransactionResult result = AgentCombatAttackRuntime.attackMonster(
                    entry, agent, skillPlan);

            assertEquals(AgentAttackTransactionResult.Status.DEFERRED, result.status());
            assertEquals(AgentAttackTransactionResult.Reason.CANNOT_USE_SKILL, result.reason());
            verify(agent, never()).getMap();
        }
    }

    @Test
    void onlyAShootingWeaponsCloseRangeBasicAttackIsDegenerate() {
        Character archer = mock(Character.class);
        Character warrior = mock(Character.class);
        AgentAttackPlan closeBasic = new AgentAttackPlan(
                0, 0, 1, null, List.of(), AgentAttackRoute.CLOSE, 0, 0, 0, 0, 6, 0, 800, null);
        AgentAttackPlan shot = new AgentAttackPlan(
                0, 0, 1, null, List.of(), AgentAttackRoute.RANGED, 0, 0, 0, 0, 6, 0, 800, null);
        AgentAttackPlan closeSkill = new AgentAttackPlan(
                3_001_004, 1, 1, null, List.of(), AgentAttackRoute.CLOSE, 0, 0, 0, 0, 6, 0, 800, null);
        try (MockedStatic<AgentAttackExecutionProvider> execution = Mockito.mockStatic(
                AgentAttackExecutionProvider.class, Mockito.CALLS_REAL_METHODS)) {
            execution.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(archer))
                    .thenReturn(WeaponType.BOW);
            execution.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(warrior))
                    .thenReturn(WeaponType.GENERAL2H_SWING);

            assertTrue(AgentCombatAttackRuntime.degenerateBasicAttack(archer, closeBasic));
            assertFalse(AgentCombatAttackRuntime.degenerateBasicAttack(archer, shot));
            assertFalse(AgentCombatAttackRuntime.degenerateBasicAttack(archer, closeSkill));
            assertFalse(AgentCombatAttackRuntime.degenerateBasicAttack(warrior, closeBasic));
        }
    }

    @Test
    void degenerateAttacksAreCountedApartAndResetWithTheHistogram() {
        AgentCombatSkillUsageState usage = new AgentCombatSkillUsageState();
        usage.record(0, 40L);
        usage.recordDegenerate();
        usage.recordDegenerate();

        assertEquals(2L, usage.degenerateAttacks());
        assertEquals(1L, usage.snapshot().get(0)[0]);
        usage.reset();
        assertEquals(0L, usage.degenerateAttacks());
    }

    @Test
    void aBossesCompanionDamagePercentScalesEveryLineAndKeepsTheDelayAndCrits() {
        net.server.channel.handlers.AbstractDealDamageHandler.AttackTarget made =
                new net.server.channel.handlers.AbstractDealDamageHandler.AttackTarget(
                        (short) 316, List.of(20_000, 0, 31_000), java.util.Set.of(2));

        var strong = AgentCombatAttackRuntime.scaled(made, 400);

        assertEquals(List.of(80_000, 0, 124_000), strong.damageLines(), "a miss stays a miss");
        assertEquals((short) 316, strong.delay());
        assertEquals(java.util.Set.of(2), strong.critLineIndices());
        assertEquals(made, AgentCombatAttackRuntime.scaled(made, 100), "100 leaves the lines as they were");
        assertEquals(List.of(Integer.MAX_VALUE), AgentCombatAttackRuntime.scaled(
                new net.server.channel.handlers.AbstractDealDamageHandler.AttackTarget(
                        (short) 0, List.of(Integer.MAX_VALUE), java.util.Set.of()), 1000).damageLines());
    }

    @Test
    void sacrificesLinesStayAsMadeSoItsHpCostDoesNotGrowWithTheBossScale() {
        assertEquals(100, AgentCombatAttackRuntime.linePercent(constants.skills.DragonKnight.SACRIFICE, 1000));
        assertEquals(1000, AgentCombatAttackRuntime.linePercent(constants.skills.DragonKnight.SPEAR_CRUSHER, 1000));
        assertEquals(100, AgentCombatAttackRuntime.linePercent(0, 100), "outside a boss run nothing changes");
    }
    // CWKPQ plan B3: a skill swung at nothing (a sigil)

    @Test
    void aSwingAtNothingTakesOnlyAPlanWithNoTargetsAndARealSkill() {
        Character agent = mock(Character.class);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        Monster target = mock(Monster.class);
        AgentAttackPlan withTarget = new AgentAttackPlan(Pirate.FLASH_FIST, 1, 1, null, List.of(target),
                AgentAttackRoute.CLOSE, 0, 0, 0, 0, 4, 0, 600, null);
        AgentAttackPlan basic = new AgentAttackPlan(0, 0, 1, null, List.of(), AgentAttackRoute.CLOSE,
                0, 0, 0, 0, 4, 0, 600, null);
        AgentAttackPlan mesoExplosion = new AgentAttackPlan(constants.skills.ChiefBandit.MESO_EXPLOSION, 1, 1, null,
                List.of(), AgentAttackRoute.CLOSE, 0, 0, 0, 0, 4, 0, 600, null);

        for (AgentAttackPlan plan : List.of(withTarget, basic, mesoExplosion)) {
            AgentAttackTransactionResult result = AgentCombatAttackRuntime.attackAtNothing(entry, agent, plan);
            assertEquals(AgentAttackTransactionResult.Reason.INVALID_REQUEST, result.reason(), plan.skillId + "");
        }
        verify(agent, never()).getMap();
    }

    @Test
    void aSealedAgentSwingsNothing() {
        Character agent = mock(Character.class);
        when(agent.hasDisease(Disease.SEAL)).thenReturn(true);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentAttackPlan plan = new AgentAttackPlan(Pirate.FLASH_FIST, 1, 1, null, List.of(),
                AgentAttackRoute.CLOSE, 0, 0, 0, 0, 4, 0, 600, null);

        try (MockedStatic<AgentAttackExecutionProvider> execution = Mockito.mockStatic(
                AgentAttackExecutionProvider.class, Mockito.CALLS_REAL_METHODS)) {
            execution.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(agent))
                    .thenReturn(WeaponType.KNUCKLE);
            AgentAttackTransactionResult result = AgentCombatAttackRuntime.attackAtNothing(entry, agent, plan);

            assertEquals(AgentAttackTransactionResult.Status.DEFERRED, result.status());
            assertEquals(AgentAttackTransactionResult.Reason.CANNOT_USE_SKILL, result.reason());
            execution.verify(() -> AgentAttackExecutionProvider.applyAttackRoute(Mockito.any(), Mockito.any(),
                    Mockito.any()), never());
        }
    }

    @Test
    void aSwingAtNothingGoesThroughTheHandlerWithNoTargetsAndKeepsTheAttackLock() {
        Character agent = mock(Character.class);
        when(agent.getMapId()).thenReturn(610_030_200);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentAttackPlan strafe = new AgentAttackPlan(3_111_006, 20, 4, new Rectangle(0, 0, 300, 80), List.of(),
                AgentAttackRoute.RANGED, 9, 1, 0, 0x8a, 4, 0, 900, WeaponType.BOW);
        List<net.server.channel.handlers.AbstractDealDamageHandler.AttackInfo> sent = new java.util.ArrayList<>();

        try (MockedStatic<AgentAttackExecutionProvider> execution = Mockito.mockStatic(
                AgentAttackExecutionProvider.class, Mockito.CALLS_REAL_METHODS);
             MockedStatic<AgentCombatSkillUsePolicy> cost = Mockito.mockStatic(AgentCombatSkillUsePolicy.class);
             MockedStatic<AgentCombatRangePolicy> range = Mockito.mockStatic(AgentCombatRangePolicy.class)) {
            execution.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(agent)).thenReturn(WeaponType.BOW);
            execution.when(() -> AgentAttackExecutionProvider.applyAttackRoute(Mockito.eq(AgentAttackRoute.RANGED),
                    Mockito.any(), Mockito.eq(agent))).thenAnswer(inv -> {
                        sent.add(inv.getArgument(1));
                        return CombatAttackApplicationResult.appliedResult();
                    });
            cost.when(() -> AgentCombatSkillUsePolicy.canPaySkillCost(agent, 3_111_006, 20)).thenReturn(true);
            range.when(() -> AgentCombatRangePolicy.canUseAttackPlanNow(Mockito.anyBoolean(), Mockito.any(),
                    Mockito.any())).thenReturn(true);

            AgentAttackTransactionResult result = AgentCombatAttackRuntime.attackAtNothing(entry, agent, strafe);

            assertTrue(result.committed(), result.toString());
            assertEquals(1, sent.size());
            assertTrue(sent.get(0).targets.isEmpty());
            assertEquals(0, sent.get(0).numAttacked);
            assertEquals(3_111_006, sent.get(0).skill);
            assertTrue(sent.get(0).ranged);
            assertTrue(AgentCombatCooldownStateRuntime.hasAttackCooldown(entry), "its animation holds the Agent");
        }
    }
}
