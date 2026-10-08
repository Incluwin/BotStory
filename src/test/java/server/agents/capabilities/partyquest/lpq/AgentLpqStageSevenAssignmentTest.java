package server.agents.capabilities.partyquest.lpq;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLpqStageSevenAssignmentTest {
    @Test
    void selectsTwoRangedNonleadersForTopAndLeavesFourAtBottom() {
        List<AgentLpqMemberState> members = List.of(
                member(101, AgentLpqMemberState.MemberType.AGENT),
                member(102, AgentLpqMemberState.MemberType.AGENT),
                member(103, AgentLpqMemberState.MemberType.AGENT),
                member(104, AgentLpqMemberState.MemberType.AGENT),
                member(105, AgentLpqMemberState.MemberType.AGENT),
                member(106, AgentLpqMemberState.MemberType.AGENT));

        List<Integer> top = AgentLpqCoordinator.stageSevenTopMemberIds(
                members, Set.of(101, 102, 103, 106)::contains, 101);

        assertEquals(List.of(102, 103), top);
        assertEquals(Set.of(101, 104, 105, 106), members.stream()
                .map(AgentLpqMemberState::characterId)
                .filter(id -> !top.contains(id)).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void keepsTwoQualifiedAgentsOnTopBeforeAQualifiedHuman() {
        List<AgentLpqMemberState> members = List.of(
                member(201, AgentLpqMemberState.MemberType.AGENT),
                member(202, AgentLpqMemberState.MemberType.AGENT),
                member(203, AgentLpqMemberState.MemberType.AGENT),
                member(204, AgentLpqMemberState.MemberType.AGENT),
                member(205, AgentLpqMemberState.MemberType.AGENT),
                member(206, AgentLpqMemberState.MemberType.HUMAN));

        assertEquals(List.of(202, 201), AgentLpqCoordinator.stageSevenTopMemberIds(
                members, Set.of(201, 202, 206)::contains, 201));
    }

    @Test
    void neverAssignsTheHumanOrAnIncapableAgentToARequiredTriggerLane() {
        List<AgentLpqMemberState> members = List.of(
                member(211, AgentLpqMemberState.MemberType.AGENT),
                member(212, AgentLpqMemberState.MemberType.AGENT),
                member(213, AgentLpqMemberState.MemberType.AGENT),
                member(214, AgentLpqMemberState.MemberType.AGENT),
                member(215, AgentLpqMemberState.MemberType.AGENT),
                member(216, AgentLpqMemberState.MemberType.HUMAN));

        assertEquals(List.of(212), AgentLpqCoordinator.stageSevenTopMemberIds(
                members, Set.of(212, 216)::contains, 211));
    }

    @Test
    void explicitlyRequestedCapableHumanOwnsOneTopLane() {
        List<AgentLpqMemberState> members = List.of(
                member(221, AgentLpqMemberState.MemberType.AGENT),
                member(222, AgentLpqMemberState.MemberType.AGENT),
                member(223, AgentLpqMemberState.MemberType.AGENT),
                member(224, AgentLpqMemberState.MemberType.AGENT),
                member(225, AgentLpqMemberState.MemberType.AGENT),
                member(226, AgentLpqMemberState.MemberType.HUMAN));

        assertEquals(List.of(226, 222), AgentLpqCoordinator.stageSevenTopMemberIds(
                members, Set.of(222, 223, 226)::contains, 221, 226));
    }

    @Test
    void sendsTheTwoQualifiedBoxWackersTop() {
        List<AgentLpqMemberState> members = List.of(
                member(301, AgentLpqMemberState.MemberType.AGENT),
                member(302, AgentLpqMemberState.MemberType.AGENT),
                member(303, AgentLpqMemberState.MemberType.AGENT),
                member(304, AgentLpqMemberState.MemberType.AGENT),
                member(305, AgentLpqMemberState.MemberType.AGENT),
                member(306, AgentLpqMemberState.MemberType.AGENT));

        assertEquals(List.of(302, 305), AgentLpqCoordinator.stageSevenTopMemberIds(
                members, Set.of(302, 305)::contains, 301));
    }

    @Test
    void mapsEveryAuthoredRatzToItsOppositeReachableFiringLedge() {
        assertEquals(new Point(-240, -990),
                AgentLpqCoordinator.stageSevenFiringAnchor(new Point(224, -1_044)));
        assertEquals(new Point(-240, -1_263),
                AgentLpqCoordinator.stageSevenFiringAnchor(new Point(219, -1_276)));
        assertEquals(new Point(-240, -1_469),
                AgentLpqCoordinator.stageSevenFiringAnchor(new Point(228, -1_543)));
        assertNull(AgentLpqCoordinator.stageSevenFiringAnchor(new Point(0, 0)));
    }

    @Test
    void toleratesSmallServerPositionDriftWithoutLosingTheAuthoredRatzLane() {
        assertEquals(new Point(-240, -990),
                AgentLpqCoordinator.stageSevenFiringAnchor(new Point(224, -1_050)));
        assertEquals(new Point(-240, -1_263),
                AgentLpqCoordinator.stageSevenFiringAnchor(new Point(219, -1_264)));
        assertEquals(new Point(-240, -1_469),
                AgentLpqCoordinator.stageSevenFiringAnchor(new Point(228, -1_520)));
        assertNull(AgentLpqCoordinator.stageSevenFiringAnchor(new Point(228, -1_700)));
    }

    @Test
    void groundedRangedShotIncludesTheAuthoredTriggerWithoutAJumpAttack() {
        Rectangle ordinaryProjectile = new Rectangle(-240, -1_519, 400, 100);
        Point trigger = new Point(228, -1_543);

        Rectangle objectiveHitBox = AgentLpqCoordinator.authoredStageSevenTriggerHitBox(
                ordinaryProjectile, trigger);

        assertTrue(objectiveHitBox.contains(trigger));
        assertTrue(objectiveHitBox.contains(ordinaryProjectile));
        assertEquals(ordinaryProjectile.x, objectiveHitBox.x);
        assertEquals(trigger.y, objectiveHitBox.y);
    }

    @Test
    void stageSevenShotWaitsForTheAgentToStandOnItsFiringLedge() {
        Point anchor = new Point(-240, -1_469);

        assertTrue(AgentLpqCoordinator.stageSevenFiringReady(anchor, true, anchor));
        assertFalse(AgentLpqCoordinator.stageSevenFiringReady(anchor, false, anchor));
        assertFalse(AgentLpqCoordinator.stageSevenFiringReady(
                new Point(-240, -1_350), true, anchor));
    }

    @Test
    void stageSevenWaitsForRatzDropsReactorsAndRombardsBeforeCombatIsClear() {
        assertFalse(AgentLpqCoordinator.stageSevenCombatCleared(1, 0, 0));
        assertFalse(AgentLpqCoordinator.stageSevenCombatCleared(0, 1, 0));
        assertFalse(AgentLpqCoordinator.stageSevenCombatCleared(0, 0, 1));
        assertTrue(AgentLpqCoordinator.stageSevenCombatCleared(0, 0, 0));
    }

    @Test
    void keepsEachRangedWorkerOnAStableAuthoredRatzLane() {
        assertEquals(List.of(-1_044, -1_276),
                AgentLpqCoordinator.stageSevenRatzPriorityYs(0));
        assertEquals(List.of(-1_543), AgentLpqCoordinator.stageSevenRatzPriorityYs(1));
        assertEquals(List.of(), AgentLpqCoordinator.stageSevenRatzPriorityYs(2));
    }

    @Test
    void oneQualifiedAgentOwnsAllThreeTriggerLanes() {
        assertEquals(List.of(-1_044, -1_276, -1_543),
                AgentLpqCoordinator.stageSevenRatzPriorityYs(0, 1));
        assertEquals(List.of(),
                AgentLpqCoordinator.stageSevenRatzPriorityYs(1, 1));
    }

    // ---- a dry shooter in a companion run (2026-10-07: Kestrel kept his ratz lane with no stars) --------------

    @Test
    void aDryCompanionIsNoStageSevenShooterInACompanionRun() {
        java.util.Map<Integer, client.Character> chars = java.util.Map.of(
                401, assassin(0), 402, assassin(800), 400, assassin(800));
        List<AgentLpqMemberState> members = List.of(member(400, AgentLpqMemberState.MemberType.HUMAN),
                member(401, AgentLpqMemberState.MemberType.AGENT), member(402, AgentLpqMemberState.MemberType.AGENT));
        try (org.mockito.MockedStatic<server.agents.capabilities.combat.AgentAttackExecutionProvider> weapons =
                     claws()) {
            List<Integer> top = AgentLpqCoordinator.stageSevenTopMemberIds(
                    members, id -> AgentLpqCoordinator.stageSevenShooter(chars.get(id), true), 400);

            assertEquals(List.of(402), top, "the loaded one moves up; the human leader keeps the bottom");
            assertEquals(List.of(-1_044, -1_276, -1_543), AgentLpqCoordinator.stageSevenRatzPriorityYs(
                    top.indexOf(402), top.size()), "a single shooter takes all three ratz");
        }
    }

    @Test
    void aStockRunKeepsTheJobOnlyPick() {
        java.util.Map<Integer, client.Character> chars = java.util.Map.of(401, assassin(0), 402, assassin(800));
        List<AgentLpqMemberState> members = List.of(member(400, AgentLpqMemberState.MemberType.AGENT),
                member(401, AgentLpqMemberState.MemberType.AGENT), member(402, AgentLpqMemberState.MemberType.AGENT));
        try (org.mockito.MockedStatic<server.agents.capabilities.combat.AgentAttackExecutionProvider> weapons =
                     claws()) {
            assertEquals(List.of(401, 402), AgentLpqCoordinator.stageSevenTopMemberIds(
                    members, id -> AgentLpqCoordinator.stageSevenShooter(chars.get(id), false), 400));
        }
    }

    @Test
    void aMemberHandedStarsIsAShooterAgain() {
        client.Character kestrel = assassin(0);
        try (org.mockito.MockedStatic<server.agents.capabilities.combat.AgentAttackExecutionProvider> weapons =
                     claws()) {
            assertFalse(AgentLpqCoordinator.stageSevenShooter(kestrel, true));
            kestrel.getInventory(client.inventory.InventoryType.USE).addItem(testutil.Items.itemWithQuantity(2_070_000, 300));
            assertTrue(AgentLpqCoordinator.stageSevenShooter(kestrel, true));
        }
    }

    /** An Assassin with a claw and {@code stars} throwing stars in a real Use tab. */
    static client.Character assassin(int stars) {
        client.Character assassin = org.mockito.Mockito.mock(client.Character.class);
        org.mockito.Mockito.when(assassin.getJob()).thenReturn(client.Job.ASSASSIN);
        org.mockito.Mockito.when(assassin.getBuffedValue(org.mockito.ArgumentMatchers.any(client.BuffStat.class)))
                .thenReturn(null); // no Shadow Claw
        client.inventory.Inventory use = new client.inventory.Inventory(assassin, client.inventory.InventoryType.USE,
                (byte) 24);
        if (stars > 0) {
            use.addItem(testutil.Items.itemWithQuantity(2_070_000, stars));
        }
        org.mockito.Mockito.when(assassin.getInventory(client.inventory.InventoryType.USE)).thenReturn(use);
        return assassin;
    }

    /** Every character holds a claw. */
    static org.mockito.MockedStatic<server.agents.capabilities.combat.AgentAttackExecutionProvider> claws() {
        org.mockito.MockedStatic<server.agents.capabilities.combat.AgentAttackExecutionProvider> weapons =
                org.mockito.Mockito.mockStatic(server.agents.capabilities.combat.AgentAttackExecutionProvider.class);
        weapons.when(() -> server.agents.capabilities.combat.AgentAttackExecutionProvider.getEquippedWeaponType(
                org.mockito.ArgumentMatchers.any())).thenReturn(client.inventory.WeaponType.CLAW);
        return weapons;
    }

    private static AgentLpqMemberState member(
            int id, AgentLpqMemberState.MemberType memberType) {
        return new AgentLpqMemberState(id, memberType);
    }
}
