package server.agents.capabilities.shop;

import server.agents.runtime.AgentRuntimeEntry;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentShopStateRuntimeTest {
    @Test
    void adaptsShopTransitionFlagsAndDelay() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);

        assertFalse(AgentShopStateRuntime.shopVisitPending(entry));
        assertFalse(AgentShopStateRuntime.shopSequenceActive(entry));
        assertFalse(AgentShopStateRuntime.hasActiveShopTransition(entry));
        assertEquals(0, AgentShopStateRuntime.shopApproachDelayMs(entry));

        AgentShopStateRuntime.startShopVisit(entry, null, null, 250, 1_000L);
        AgentShopStateRuntime.markShopSequenceActive(entry, 2_000L);

        assertTrue(AgentShopStateRuntime.shopVisitPending(entry));
        assertTrue(AgentShopStateRuntime.shopSequenceActive(entry));
        assertTrue(AgentShopStateRuntime.hasActiveShopTransition(entry));
        assertEquals(250, AgentShopStateRuntime.shopApproachDelayMs(entry));
    }

    @Test
    void adaptsShopTargetsWithFallbackAndPointCopies() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);

        assertNull(AgentShopStateRuntime.activeShopTargetPosition(entry));

        AgentShopStateRuntime.startShopVisit(entry, new Point(100, 200), null, 0, 1_000L);

        Point npcPosition = AgentShopStateRuntime.shopNpcPosition(entry);
        npcPosition.x = 999;

        assertEquals(new Point(100, 200), AgentShopStateRuntime.shopNpcPosition(entry));
        assertEquals(new Point(100, 200), AgentShopStateRuntime.activeShopTargetPosition(entry));

        AgentShopStateRuntime.startShopVisit(entry, new Point(100, 200), new Point(300, 400), 0, 1_000L);

        Point targetPosition = AgentShopStateRuntime.shopTargetPosition(entry);
        targetPosition.y = 888;

        assertEquals(new Point(300, 400), AgentShopStateRuntime.shopTargetPosition(entry));
        assertEquals(new Point(300, 400), AgentShopStateRuntime.activeShopTargetPosition(entry));
    }

    @Test
    void adaptsShopVisitLifecycleAndClearing() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);

        AgentShopStateRuntime.startShopVisit(
                entry,
                new Point(10, 20),
                new Point(30, 40),
                350,
                0,
                2030003,
                1,
                1_000L);
        AgentShopStateRuntime.setShopSellTrashPending(entry, true);

        assertTrue(AgentShopStateRuntime.shopVisitPending(entry));
        assertTrue(AgentShopStateRuntime.shopSellTrashPending(entry));
        assertEquals(new Point(10, 20), AgentShopStateRuntime.shopNpcPosition(entry));
        assertEquals(new Point(30, 40), AgentShopStateRuntime.shopTargetPosition(entry));
        assertEquals(350, AgentShopStateRuntime.shopApproachDelayMs(entry));
        // The 350 ms approach delay does not count against the visit timeout.
        assertFalse(AgentShopStateRuntime.visitTimedOut(entry, 31_001L, 30_000L));
        assertTrue(AgentShopStateRuntime.visitTimedOut(entry, 31_351L, 30_000L));

        AgentShopStateRuntime.markShopSequenceActive(entry, 2_000L);

        assertTrue(AgentShopStateRuntime.shopSequenceActive(entry));
        assertFalse(AgentShopStateRuntime.visitTimedOut(entry, 40_000L, 30_000L));
        assertTrue(AgentShopStateRuntime.sequenceTimedOut(entry, 47_001L, 45_000L));

        AgentShopStateRuntime.clearShopState(entry);

        assertFalse(AgentShopStateRuntime.shopVisitPending(entry));
        assertFalse(AgentShopStateRuntime.shopSequenceActive(entry));
        assertFalse(AgentShopStateRuntime.shopSellTrashPending(entry));
        assertNull(AgentShopStateRuntime.shopNpcPosition(entry));
        assertNull(AgentShopStateRuntime.shopTargetPosition(entry));
        assertEquals(0, AgentShopStateRuntime.shopApproachDelayMs(entry));
        assertTrue(AgentShopStateRuntime.lastVisitRequestedItem(entry, 2030003, 1));
        assertFalse(AgentShopStateRuntime.lastVisitRequestedItem(entry, 2030005, 1));

        AgentShopStateRuntime.startShopVisit(
                entry,
                new Point(10, 20),
                new Point(30, 40),
                350,
                0,
                2030005,
                2,
                50_000L);

        assertFalse(AgentShopStateRuntime.lastVisitRequestedItem(entry, 2030003, 1));

        AgentShopStateRuntime.clearShopState(entry);

        assertTrue(AgentShopStateRuntime.lastVisitRequestedItem(entry, 2030005, 2));
    }

    @Test
    void adaptsShopStuckFallbackAndSequenceValidation() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        Point npc = new Point(100, 100);
        Point approach = new Point(125, 100);

        AgentShopStateRuntime.startShopVisit(entry, npc, approach, 0, 1_000L);

        assertFalse(AgentShopStateRuntime.stuckNearNpc(entry, new Point(110, 100), 2_000L, 1_000L, 2, 100));
        assertFalse(AgentShopStateRuntime.stuckNearNpc(entry, new Point(111, 100), 2_500L, 1_000L, 2, 100));
        assertTrue(AgentShopStateRuntime.stuckNearNpc(entry, new Point(111, 100), 3_100L, 1_000L, 2, 100));

        assertFalse(AgentShopStateRuntime.sequenceValid(entry, new Point(125, 100), npc, 100));

        AgentShopStateRuntime.markShopSequenceActive(entry, 3_200L);

        assertTrue(AgentShopStateRuntime.sequenceValid(entry, new Point(125, 100), npc, 100));
        assertFalse(AgentShopStateRuntime.sequenceValid(entry, new Point(400, 400), npc, 100));
    }

    @Test
    void aScheduledStepRunsOnlyForTheVisitThatScheduledIt() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        AgentShopStateRuntime.startShopVisit(entry, new Point(100, 200), null, 0, 1_000L);
        long first = AgentShopStateRuntime.shopVisitGeneration(entry);
        assertTrue(AgentShopStateRuntime.shouldRunScheduledShopStep(entry, first));

        // Asking for the open visit again keeps it: its steps still run.
        AgentShopStateRuntime.startShopVisit(entry, new Point(100, 200), null, 0, 1_500L);
        assertTrue(AgentShopStateRuntime.shouldRunScheduledShopStep(entry, first));

        // The visit ends and the next one opens: the first visit's steps no longer run.
        AgentShopStateRuntime.clearShopState(entry);
        assertFalse(AgentShopStateRuntime.shouldRunScheduledShopStep(entry, first));
        AgentShopStateRuntime.startShopVisit(entry, new Point(100, 200), null, 0, 2_000L);
        assertFalse(AgentShopStateRuntime.shouldRunScheduledShopStep(entry, first));
        assertTrue(AgentShopStateRuntime.shouldRunScheduledShopStep(
                entry, AgentShopStateRuntime.shopVisitGeneration(entry)));
    }

    @Test
    void clearingWithNoVisitOpenEndsNoVisit() {
        // every movement command and map change clears the shop state; only an open visit ends there
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        long before = AgentShopStateRuntime.shopVisitGeneration(entry);
        AgentShopStateRuntime.clearShopState(entry);
        AgentShopStateRuntime.clearShopState(entry);
        assertEquals(before, AgentShopStateRuntime.shopVisitGeneration(entry));

        AgentShopStateRuntime.startShopVisit(entry, new Point(100, 200), null, 0, 1_000L);
        AgentShopStateRuntime.clearShopState(entry);
        assertEquals(before + 1, AgentShopStateRuntime.shopVisitGeneration(entry));
        AgentShopStateRuntime.clearShopState(entry);
        assertEquals(before + 1, AgentShopStateRuntime.shopVisitGeneration(entry));
    }
}
