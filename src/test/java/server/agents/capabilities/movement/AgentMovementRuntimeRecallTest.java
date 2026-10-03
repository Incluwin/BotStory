package server.agents.capabilities.movement;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.dialogue.AgentActiveModeRuntime;
import server.agents.capabilities.dialogue.AgentChatMovementFlow;
import server.agents.capabilities.supplies.AgentPotionService;
import server.agents.capabilities.supplies.AgentSupplyProcurementState;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.agents.integration.AgentRelationshipRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentSchedulerRuntime;

import java.awt.Point;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * An owner's movement order wins over a readiness restock trip (review 2026-10-02: "follow me" and "wait here" were
 * ignored for up to the trip's four minutes while the companion walked on to the shop). Every order the owner's chat
 * and the companion card send (AgentMovementRuntime's callbacks: follow, stop, move here, hunt here, patrol, grind)
 * calls the Agent back first; follow ends the trip following, the others where it stands. A critical trip is left
 * alone.
 */
class AgentMovementRuntimeRecallTest {
    private MockedStatic<AgentSchedulerRuntime> scheduler;
    private MockedStatic<AgentMovementCommandRuntime> commands;
    private MockedStatic<AgentDialogueTransportRuntime> replies;
    private MockedStatic<AgentActiveModeRuntime> gear;
    private MockedStatic<AgentPotionService> potions;
    private MockedStatic<AgentMovementStatusRuntime> status;

    @BeforeEach
    void ordersRunAtOnce() {
        scheduler = mockStatic(AgentSchedulerRuntime.class);
        scheduler.when(() -> AgentSchedulerRuntime.afterRandomDelay(any(AgentRuntimeEntry.class), anyInt(), anyInt(),
                any(Runnable.class))).thenAnswer(inv -> {
                    ((Runnable) inv.getArgument(3)).run();
                    return null;
                });
        commands = mockStatic(AgentMovementCommandRuntime.class);
        replies = mockStatic(AgentDialogueTransportRuntime.class);
        gear = mockStatic(AgentActiveModeRuntime.class);
        potions = mockStatic(AgentPotionService.class);
        status = mockStatic(AgentMovementStatusRuntime.class);
    }

    @AfterEach
    void close() {
        status.close();
        potions.close();
        gear.close();
        replies.close();
        commands.close();
        scheduler.close();
    }

    @Test
    void everyOwnerMovementOrderCallsTheAgentBackFromItsReadinessTrip() {
        Map<String, Consumer<AgentChatMovementFlow.MovementCallbacks>> orders = new LinkedHashMap<>();
        orders.put("follow", AgentChatMovementFlow.MovementCallbacks::follow);
        orders.put("stop", AgentChatMovementFlow.MovementCallbacks::stop);
        orders.put("move here", AgentChatMovementFlow.MovementCallbacks::moveHere);
        orders.put("hunt here", AgentChatMovementFlow.MovementCallbacks::farmHere);
        orders.put("patrol", AgentChatMovementFlow.MovementCallbacks::patrol);
        orders.put("grind", AgentChatMovementFlow.MovementCallbacks::grind);

        for (Map.Entry<String, Consumer<AgentChatMovementFlow.MovementCallbacks>> order : orders.entrySet()) {
            AgentRuntimeEntry entry = companion();
            AgentSupplyProcurementState trip = trip(entry, "readiness:HP_POTION:1");

            order.getValue().accept(AgentMovementRuntime.movementCallbacks(entry));

            assertTrue(trip.recalled(), order.getKey() + " calls it back");
            assertEquals(AgentSupplyProcurementState.Phase.RETURNING, trip.phase(), order.getKey());
            assertEquals(order.getKey().equals("follow"), trip.resumeFollow(), order.getKey() + " ends it following");
        }
    }

    @Test
    void aCriticalTripGoesOnWhateverTheOrder() {
        AgentRuntimeEntry entry = companion();
        AgentSupplyProcurementState trip = trip(entry, "supply:HP_POTION:1");

        AgentMovementRuntime.movementCallbacks(entry).stop();

        assertFalse(trip.recalled());
        assertFalse(trip.abandoned());
    }

    private static AgentRuntimeEntry companion() {
        Character bot = mock(Character.class);
        Character owner = mock(Character.class);
        when(owner.getPosition()).thenReturn(new Point(100, 200));
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, owner, null);
        AgentRelationshipRuntime.setInteractionTarget(entry, owner);
        return entry;
    }

    private static AgentSupplyProcurementState trip(AgentRuntimeEntry entry, String requestId) {
        AgentSupplyProcurementState trip = entry.capabilityStates().require(AgentSupplyProcurementState.STATE_KEY);
        trip.start(requestId, "maintenance:resupply:" + requestId, AgentResourceCategory.HP_POTION, 103_000_002,
                1_051_002, 103_000_000, AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER);
        trip.rememberForegroundMode(true, false);
        return trip;
    }
}
