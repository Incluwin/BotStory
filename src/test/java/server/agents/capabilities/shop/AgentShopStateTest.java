package server.agents.capabilities.shop;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentShopStateTest {
    @Test
    void plannedVisitCarriesMandatoryTravelItem() {
        AgentShopState state = new AgentShopState();

        state.startVisit(new java.awt.Point(10, 20), new java.awt.Point(15, 20),
                0, 100, 2_030_005, 1, 1L);

        assertEquals(2_030_005, state.requiredItemId());
        assertEquals(1, state.requiredItemCount());
        state.clear();
        assertEquals(0, state.requiredItemId());
        assertEquals(0, state.requiredItemCount());
    }
    @Test
    void tracksShopVisitLifecycleAndClearing() {
        AgentShopState state = new AgentShopState();

        state.startVisit(new Point(10, 20), new Point(30, 40), 350, 1_000L);
        state.setSellTrashPending(true);

        assertTrue(state.visitPending());
        assertTrue(state.sellTrashPending());
        assertEquals(new Point(10, 20), state.npcPosition());
        assertEquals(new Point(30, 40), state.targetPosition());
        assertEquals(350, state.approachDelayMs());
        // The 350 ms approach delay does not count against the visit timeout.
        assertFalse(state.visitTimedOut(31_001L, 30_000L));
        assertTrue(state.visitTimedOut(31_351L, 30_000L));

        state.markSequenceActive(2_000L);

        assertTrue(state.sequenceActive());
        assertFalse(state.visitTimedOut(40_000L, 30_000L));
        assertTrue(state.sequenceTimedOut(47_001L, 45_000L));

        state.clear();

        assertFalse(state.visitPending());
        assertFalse(state.sequenceActive());
        assertFalse(state.sellTrashPending());
        assertNull(state.npcPosition());
        assertNull(state.targetPosition());
        assertEquals(0, state.approachDelayMs());
        assertEquals(0, state.minimumMesoReserve());
    }

    @Test
    void plannedSalesExtendOnlyTheOpenTransaction() {
        AgentShopState state = new AgentShopState();
        state.startVisit(new Point(10, 20), new Point(30, 40), 0, 1_000L);
        state.extendSequence(60_000L); // nothing to extend before the shopkeeper's window opens

        state.markSequenceActive(2_000L);
        assertTrue(state.sequenceTimedOut(47_001L, 45_000L));

        state.extendSequence(60_000L);
        assertEquals(60_000L, state.sequenceAllowanceMs());
        assertFalse(state.sequenceTimedOut(107_000L, 45_000L));
        assertTrue(state.sequenceTimedOut(107_001L, 45_000L), "a stalled sale still times out");

        state.clear();
        state.startVisit(new Point(10, 20), new Point(30, 40), 0, 200_000L);
        state.markSequenceActive(201_000L);
        assertEquals(0L, state.sequenceAllowanceMs());
        assertTrue(state.sequenceTimedOut(246_001L, 45_000L));
    }

    @Test
    void anApproachThatComesNoCloserStallsAndOneStepCloserRestartsTheClock() {
        AgentShopState state = new AgentShopState();
        state.startVisit(new Point(10, 20), new Point(30, 40), 0, 1_000L);
        assertFalse(state.approachStalled(100_000L, 30_000L), "no look yet: no clock");

        state.noteApproach(2_000, 2, 2_000L);
        state.noteApproach(2_001, 2, 20_000L); // a step back, and the closest it has been stays
        state.noteApproach(1_999, 2, 25_000L); // a pixel closer is no progress
        assertFalse(state.approachStalled(32_000L, 30_000L));
        assertTrue(state.approachStalled(32_001L, 30_000L));

        state.noteApproach(1_900, 2, 32_001L);
        assertFalse(state.approachStalled(62_001L, 30_000L), "closer: its clock starts again");

        state.markSequenceActive(62_001L);
        assertFalse(state.approachStalled(200_000L, 30_000L), "at the counter nothing stalls the walk");
        state.clear();
        state.startVisit(new Point(10, 20), new Point(30, 40), 0, 300_000L);
        assertFalse(state.approachStalled(400_000L, 30_000L), "a new visit starts a new walk");
    }

    @Test
    void returnsDefensivePointCopiesAndFallsBackToNpcTarget() {
        AgentShopState state = new AgentShopState();

        assertNull(state.activeTargetPosition());

        Point npc = new Point(100, 200);
        state.startVisit(npc, null, 0, 1_000L);
        npc.x = 999;
        Point npcPosition = state.npcPosition();
        npcPosition.x = 888;

        assertEquals(new Point(100, 200), state.npcPosition());
        assertEquals(new Point(100, 200), state.activeTargetPosition());
    }

    @Test
    void tracksStuckFallbackAndSequenceValidation() {
        AgentShopState state = new AgentShopState();
        Point npc = new Point(100, 100);
        Point approach = new Point(125, 100);

        state.startVisit(npc, approach, 0, 1_000L);

        assertFalse(state.stuckNearNpc(new Point(110, 100), 2_000L, 1_000L, 2, 100));
        assertFalse(state.stuckNearNpc(new Point(111, 100), 2_500L, 1_000L, 2, 100));
        assertTrue(state.stuckNearNpc(new Point(111, 100), 3_100L, 1_000L, 2, 100));

        assertFalse(state.sequenceValid(new Point(125, 100), npc, 100));

        state.markSequenceActive(3_200L);

        assertTrue(state.sequenceValid(new Point(125, 100), npc, 100));
        assertFalse(state.sequenceValid(new Point(400, 400), npc, 100));
    }

    @Test
    void preservesTheLargestRequiredMesoReserveForAVisit() {
        AgentShopState state = new AgentShopState();

        state.startVisit(new Point(10, 20), null, 0, 1_000, 1_000L);
        state.ensureMinimumMesoReserve(500);
        assertEquals(1_000, state.minimumMesoReserve());

        state.ensureMinimumMesoReserve(1_500);
        assertEquals(1_500, state.minimumMesoReserve());

        state.startVisit(new Point(30, 40), null, 0, 0, 2_000L);
        assertEquals(0, state.minimumMesoReserve());

        state.clear();
        assertEquals(0, state.minimumMesoReserve());
    }

    /**
     * Companions sell their junk at every shop visit (2026-10-10): a sale the owner or a supply trip asked for
     * (ASKED) always wins over the quiet one every visit makes (ROUTINE), and a clear drops both and the sale-only mark.
     */
    @Test
    void askedWinsOverRoutineAndClearDropsBoth() {
        AgentShopState state = new AgentShopState();
        state.setSellTrashPending(true);
        assertEquals(AgentShopState.Sale.ASKED, state.sale());
        state.requestRoutineSale();
        assertEquals(AgentShopState.Sale.ASKED, state.sale(), "a routine sale never downgrades an asked one");
        assertTrue(state.saleAsked());
        assertFalse(state.routineSale());

        AgentShopState fresh = new AgentShopState();
        assertEquals(AgentShopState.Sale.NONE, fresh.sale());
        assertFalse(fresh.sellTrashPending());
        fresh.requestRoutineSale();
        assertEquals(AgentShopState.Sale.ROUTINE, fresh.sale());
        assertTrue(fresh.routineSale());
        assertFalse(fresh.saleAsked());
        assertTrue(fresh.sellTrashPending(), "a routine sale is a pending sale for every current reader");
        fresh.markSaleOnly();
        assertTrue(fresh.saleOnly());

        fresh.clear();
        assertEquals(AgentShopState.Sale.NONE, fresh.sale());
        assertFalse(fresh.sellTrashPending());
        assertFalse(fresh.saleOnly());

        fresh.setSellTrashPending(true);
        fresh.setSellTrashPending(false);
        assertEquals(AgentShopState.Sale.NONE, fresh.sale());
    }

    @Test
    void startVisitDropsTheSaleOnlyMarkAndKeepsTheSale() {
        AgentShopState state = new AgentShopState();
        state.requestRoutineSale();
        state.markSaleOnly();

        // a supply trip or "sell junk" that reaches a sale-only visit buys and speaks as today
        state.startVisit(new Point(10, 20), new Point(30, 40), 0, 0, 0, 0, 1_000L);

        assertFalse(state.saleOnly());
        assertTrue(state.routineSale(), "the sale is kept");
    }

    @Test
    void theOwnerShopMapSurvivesAClear() {
        AgentShopState state = new AgentShopState();
        assertEquals(0, state.ownerShopSaleMapId());
        state.setOwnerShopSaleMapId(100_000_100);
        state.startVisit(new Point(10, 20), null, 0, 1_000L);
        state.clear();
        assertEquals(100_000_100, state.ownerShopSaleMapId(), "one look per map visit outlives the visit");
        state.setOwnerShopSaleMapId(0);
        assertEquals(0, state.ownerShopSaleMapId());
    }
}
