package server.agents.capabilities.looting;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentAttackExecutionProvider;
import server.agents.capabilities.looting.AgentGrindLootStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.maps.MapObject;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class AgentGrindLootTargetServiceTest {
    @Test
    void absentObjectiveDropsDoNotClearOrdinaryLootAlreadyBeingApproached() {
        ObjectiveFixture fixture = new ObjectiveFixture();
        AgentGrindLootStateRuntime.setGrindLootTarget(fixture.entry, fixture.far);
        try (var graphs = mockStatic(server.agents.capabilities.navigation.AgentNavigationGraphService.class)) {
            assertFalse(AgentGrindLootTargetService.prepareNearestObjectiveItem(
                    fixture.entry, fixture.agent, java.util.Set.of(4001318), 10_000L));
            assertSame(fixture.far, AgentGrindLootStateRuntime.grindLootTarget(fixture.entry));
        }
    }

    @Test
    void objectiveLootAbandonsAStuckDropAndTriesAnAlternativeWithoutPickingUpRemotely() {
        ObjectiveFixture fixture = new ObjectiveFixture();
        try (var eligibility = mockStatic(AgentLootEligibility.class);
             var graphs = mockStatic(server.agents.capabilities.navigation.AgentNavigationGraphService.class)) {
            eligibility.when(() -> AgentLootEligibility.canBotTargetLoot(any(), any(), any(), any(),
                    org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
            assertTrue(fixture.prepare(10_000L));
            assertSame(fixture.near, AgentGrindLootStateRuntime.grindLootTarget(fixture.entry));
            assertTrue(fixture.prepare(40_001L));
            assertSame(fixture.far, AgentGrindLootStateRuntime.grindLootTarget(fixture.entry));
            assertTrue(AgentGrindLootStateRuntime.isRetrySuppressed(fixture.entry, fixture.near, 40_001L));
            assertTrue(fixture.prepare(41_000L));
            assertSame(fixture.far, AgentGrindLootStateRuntime.grindLootTarget(fixture.entry));
            org.mockito.Mockito.verify(fixture.agent, org.mockito.Mockito.never()).pickupItem(any());
        }
    }

    @Test
    void objectiveLootHonoursRetrySuppressionAndChangedObjectiveItems() {
        ObjectiveFixture fixture = new ObjectiveFixture();
        try (var eligibility = mockStatic(AgentLootEligibility.class);
             var graphs = mockStatic(server.agents.capabilities.navigation.AgentNavigationGraphService.class)) {
            eligibility.when(() -> AgentLootEligibility.canBotTargetLoot(any(), any(), any(), any(),
                    org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
            AgentGrindLootStateRuntime.suppressRetry(fixture.entry, fixture.near, 50_000L);
            assertTrue(fixture.prepare(10_000L));
            assertSame(fixture.far, AgentGrindLootStateRuntime.grindLootTarget(fixture.entry));
            assertFalse(AgentGrindLootTargetService.prepareNearestObjectiveItem(
                    fixture.entry, fixture.agent, java.util.Set.of(4001318), 11_000L));
            assertFalse(AgentGrindLootStateRuntime.hasObjectiveLootTarget(fixture.entry));
        }
    }

    @Test
    void objectiveLootSkipsTheNearestDropWhenItsRouteIsNotExecutable() {
        ObjectiveFixture fixture = new ObjectiveFixture();
        var graph = mock(server.agents.capabilities.navigation.AgentNavigationGraph.class);
        when(graph.findRegionId(fixture.map, new Point())).thenReturn(1);
        when(graph.findRegionId(fixture.map, fixture.near.getPosition())).thenReturn(2);
        when(graph.findRegionId(fixture.map, fixture.far.getPosition())).thenReturn(3);
        try (var eligibility = mockStatic(AgentLootEligibility.class);
             var graphs = mockStatic(server.agents.capabilities.navigation.AgentNavigationGraphService.class);
             var paths = mockStatic(server.agents.capabilities.navigation.AgentNavigationPathService.class)) {
            eligibility.when(() -> AgentLootEligibility.canBotTargetLoot(any(), any(), any(), any(),
                    org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
            graphs.when(() -> server.agents.capabilities.navigation.AgentNavigationGraphService
                    .peekBestGraph(any(), any())).thenReturn(graph);
            paths.when(() -> server.agents.capabilities.navigation.AgentNavigationPathService.reliableRouteCost(
                    graph, fixture.map, new Point(), 1, fixture.near.getPosition(), 2,
                    0L, fixture.entry, fixture.agent, Long.MAX_VALUE)).thenReturn(Long.MAX_VALUE);
            assertTrue(fixture.prepare(10_000L));
            assertSame(fixture.far, AgentGrindLootStateRuntime.grindLootTarget(fixture.entry));
        }
    }

    private static final class ObjectiveFixture {
        final Character agent = mock(Character.class);
        final MapleMap map = mock(MapleMap.class);
        final AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        final MapItem near = mockLoot(1, false);
        final MapItem far = mockLoot(2, false);

        ObjectiveFixture() {
            when(agent.getMap()).thenReturn(map);
            when(agent.getMapId()).thenReturn(106020100);
            when(agent.getPosition()).thenReturn(new Point());
            when(near.getPosition()).thenReturn(new Point(150, 0));
            when(far.getPosition()).thenReturn(new Point(300, 0));
            for (MapItem drop : List.of(near, far)) {
                when(drop.getItemId()).thenReturn(4000499);
                when(map.getMapObject(drop.getObjectId())).thenReturn(drop);
            }
            when(map.getDroppedItems()).thenReturn(List.of(near, far));
        }

        boolean prepare(long nowMs) {
            return AgentGrindLootTargetService.prepareNearestObjectiveItem(
                    entry, agent, java.util.Set.of(4000499), nowMs);
        }
    }

    @Test
    void validateCachedTargetKeepsLiveMapObject() {
        MapleMap map = mock(MapleMap.class);
        Character agent = mock(Character.class);
        when(agent.getMap()).thenReturn(map);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, mock(Character.class), null);
        MapItem loot = mockLoot(7, false);
        when(map.getMapObject(7)).thenReturn(loot);
        AgentGrindLootStateRuntime.setGrindLootTarget(entry, loot);

        AgentGrindLootTargetService.validateCachedGrindLootTarget(entry, agent);

        assertSame(loot, AgentGrindLootStateRuntime.grindLootTarget(entry));
    }

    @Test
    void validateCachedTargetClearsPickedUpOrStaleMapObject() {
        MapleMap map = mock(MapleMap.class);
        Character agent = mock(Character.class);
        when(agent.getMap()).thenReturn(map);
        AgentRuntimeEntry pickedEntry = new AgentRuntimeEntry(agent, mock(Character.class), null);
        MapItem pickedLoot = mockLoot(7, true);
        AgentGrindLootStateRuntime.setGrindLootTarget(pickedEntry, pickedLoot);

        AgentGrindLootTargetService.validateCachedGrindLootTarget(pickedEntry, agent);

        assertNull(AgentGrindLootStateRuntime.grindLootTarget(pickedEntry));

        AgentRuntimeEntry staleEntry = new AgentRuntimeEntry(agent, mock(Character.class), null);
        MapItem staleLoot = mockLoot(8, false);
        AgentGrindLootStateRuntime.setGrindLootTarget(staleEntry, staleLoot);
        when(map.getMapObject(8)).thenReturn(null);

        AgentGrindLootTargetService.validateCachedGrindLootTarget(staleEntry, agent);

        assertNull(AgentGrindLootStateRuntime.grindLootTarget(staleEntry));
    }

    @Test
    void refreshDoesNothingWhenAiTickIsNotDue() {
        Character agent = mock(Character.class);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, mock(Character.class), null);
        MapItem existingLoot = mockLoot(9, false);
        AgentGrindLootStateRuntime.setGrindLootTarget(entry, existingLoot);

        AgentGrindLootTargetService.refreshGrindLootTarget(entry, agent, false, 100);

        assertSame(existingLoot, AgentGrindLootStateRuntime.grindLootTarget(entry));
    }

    @Test
    void refreshPreservesAnActivityAssignedLootObjective() {
        Character agent = mock(Character.class);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, mock(Character.class), null);
        MapItem objective = mockLoot(10, false);
        AgentGrindLootStateRuntime.setObjectiveLootTarget(entry, objective);

        AgentGrindLootTargetService.refreshGrindLootTarget(entry, agent, true, 100);

        assertSame(objective, AgentGrindLootStateRuntime.grindLootTarget(entry));
        assertTrue(AgentGrindLootStateRuntime.hasObjectiveLootTarget(entry));
    }

    @Test
    void meleeWaitsBrieflyThenMovesToItsRecentDrop() {
        long now = System.currentTimeMillis();
        MapleMap map = mock(MapleMap.class);
        Character agent = mock(Character.class);
        Inventory equipped = mock(Inventory.class);
        when(agent.getMap()).thenReturn(map);
        when(agent.getPosition()).thenReturn(new Point(0, 0));
        when(agent.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, mock(Character.class), null);
        entry.capabilityStates().require(AgentPostKillLootState.STATE_KEY).recordKill(42, now);

        MapObject dropper = mock(MapObject.class);
        when(dropper.getObjectId()).thenReturn(42);
        MapItem drop = mockLoot(10, false);
        when(drop.getDropper()).thenReturn(dropper);
        when(drop.getPosition()).thenReturn(new Point(120, 0));
        when(drop.getDropTime()).thenReturn(now - 500L);
        when(drop.canBePickedBy(any(Character.class))).thenReturn(true);
        when(drop.getMeso()).thenReturn(1);
        when(map.getDroppedItems()).thenReturn(List.of(drop));
        when(map.getMapObject(10)).thenReturn(drop);

        assertEquals(
                new Point(0, 0),
                AgentGrindLootTargetService.immediateMeleeLootPosition(
                        entry, agent, agent.getPosition(), 100, now));
        assertEquals(
                new Point(0, 0),
                AgentGrindLootTargetService.immediateMeleeLootPosition(
                        entry, agent, agent.getPosition(), 100, now + 300L));
        assertEquals(
                new Point(120, 0),
                AgentGrindLootTargetService.immediateMeleeLootPosition(
                        entry, agent, agent.getPosition(), 100, now + 500L));
    }

    @Test
    void schedulingRecentLootDoesNotForgetTheKillBeforePickup() {
        long now = System.currentTimeMillis();
        MapleMap map = mock(MapleMap.class);
        Character agent = mock(Character.class);
        Inventory emptyInventory = mock(Inventory.class);
        when(agent.getMap()).thenReturn(map);
        when(agent.getPosition()).thenReturn(new Point(0, 0));
        for (InventoryType type : InventoryType.values()) {
            when(agent.getInventory(type)).thenReturn(emptyInventory);
        }
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, mock(Character.class), null);
        AgentPostKillLootState state =
                entry.capabilityStates().require(AgentPostKillLootState.STATE_KEY);
        state.recordKill(42, now);

        MapObject dropper = mock(MapObject.class);
        when(dropper.getObjectId()).thenReturn(42);
        MapItem drop = mockLoot(10, false);
        when(drop.getDropper()).thenReturn(dropper);
        when(drop.getPosition()).thenReturn(new Point(120, 0));
        when(drop.getDropTime()).thenReturn(
                now - AgentLootCollectionPolicyConfig.meleeRecentKillTargetAgeMs());
        when(drop.canBePickedBy(any(Character.class))).thenReturn(true);
        when(drop.getMeso()).thenReturn(1);
        when(map.getDroppedItems()).thenReturn(List.of(drop));
        when(map.getMapObject(10)).thenReturn(drop);

        AgentGrindLootTargetService.refreshGrindLootTarget(entry, agent, true, 100, true);

        assertSame(drop, AgentGrindLootStateRuntime.grindLootTarget(entry));
        assertTrue(state.snapshot(now).killedObjectIds().contains(42));
    }

    @Test
    void recentKillWaitsForDropToSettleBeforeAnotherTargetSearch() {
        long now = System.currentTimeMillis();
        MapleMap map = mock(MapleMap.class);
        Character agent = mock(Character.class);
        when(agent.getMap()).thenReturn(map);
        when(agent.getPosition()).thenReturn(new Point(0, 0));
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, mock(Character.class), null);
        entry.capabilityStates().require(AgentPostKillLootState.STATE_KEY).recordKill(42, now);
        when(map.getDroppedItems()).thenReturn(List.of());

        assertTrue(AgentGrindLootTargetService.preparePostKillLootBeforeTargetSearch(
                entry, agent, true, 100, now));
        assertNull(AgentGrindLootStateRuntime.grindLootTarget(entry));
    }

    @Test
    void rangedKillDoesNotPauseTargetSearchBeforeLootBatchIsDue() {
        long now = System.currentTimeMillis();
        MapleMap map = mock(MapleMap.class);
        Character agent = mock(Character.class);
        Inventory equipped = mock(Inventory.class);
        when(agent.getMap()).thenReturn(map);
        when(agent.getPosition()).thenReturn(new Point(0, 0));
        when(agent.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        when(map.getDroppedItems()).thenReturn(List.of());
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, mock(Character.class), null);
        entry.capabilityStates().require(AgentPostKillLootState.STATE_KEY).recordKill(42, now);

        try (MockedStatic<AgentAttackExecutionProvider> attacks =
                     mockStatic(AgentAttackExecutionProvider.class)) {
            attacks.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(agent))
                    .thenReturn(WeaponType.GUN);

            assertFalse(AgentGrindLootTargetService.preparePostKillLootBeforeTargetSearch(
                    entry, agent, true, 100, now));
        }
        assertNull(AgentGrindLootStateRuntime.grindLootTarget(entry));
    }

    @Test
    void recentKillSelectsDropBeyondImmediateMeleeRadiusBeforeRemoteCombat() {
        long now = System.currentTimeMillis();
        MapleMap map = mock(MapleMap.class);
        Character agent = mock(Character.class);
        Inventory emptyInventory = mock(Inventory.class);
        when(agent.getMap()).thenReturn(map);
        when(agent.getPosition()).thenReturn(new Point(0, 0));
        for (InventoryType type : InventoryType.values()) {
            when(agent.getInventory(type)).thenReturn(emptyInventory);
        }
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, mock(Character.class), null);
        entry.capabilityStates().require(AgentPostKillLootState.STATE_KEY)
                .recordKill(42, now - 2_000L);

        MapObject dropper = mock(MapObject.class);
        when(dropper.getObjectId()).thenReturn(42);
        MapItem drop = mockLoot(11, false);
        when(drop.getDropper()).thenReturn(dropper);
        when(drop.getPosition()).thenReturn(new Point(400, 0));
        when(drop.getDropTime()).thenReturn(now - 2_000L);
        when(drop.canBePickedBy(any(Character.class))).thenReturn(true);
        when(drop.getMeso()).thenReturn(1);
        when(map.getDroppedItems()).thenReturn(List.of(drop));
        when(map.getMapObject(11)).thenReturn(drop);

        assertTrue(AgentGrindLootTargetService.preparePostKillLootBeforeTargetSearch(
                entry, agent, true, 100, now));
        assertSame(drop, AgentGrindLootStateRuntime.grindLootTarget(entry));
    }

    private static MapItem mockLoot(int objectId, boolean pickedUp) {
        MapItem loot = mock(MapItem.class);
        when(loot.getObjectId()).thenReturn(objectId);
        when(loot.isPickedUp()).thenReturn(pickedUp);
        return loot;
    }
}
