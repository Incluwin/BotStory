package server.agents.capabilities.supplies;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.contracts.AgentProcurementMethod;
import server.agents.capabilities.contracts.AgentProcurementRequest;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.contracts.AgentSupplyNeed;
import server.agents.capabilities.contracts.AgentSupplyUrgency;
import server.agents.integration.AgentRelationshipRuntime;
import server.agents.objectives.AgentObjectiveDefinition;
import server.agents.objectives.AgentObjectiveJournalEntry;
import server.agents.objectives.AgentObjectiveKernel;
import server.agents.objectives.AgentObjectiveSource;
import server.agents.objectives.AgentObjectiveState;
import server.agents.objectives.AgentObjectiveStatus;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A resupply walk that stands on one map for TRAVEL_NO_PROGRESS_MS is ended. Live 2026-10-09: four companions on a
 * critical HP potion trip from the Balrog stairway stood at Another Entrance's arrival portal (105090200) from about
 * 01:58 to 02:06 and later, phase TRAVEL_TO_SUPPLIER, with no line logged. The Agents here stand on 105090200 with no
 * live map, so no portal resolves and the walk never leaves it.
 */
class AgentSupplyTravelWatchdogTest {
    private static final int ANOTHER_ENTRANCE = 105090200;
    private static final int OTHER_MAP = 105090100;
    private static final int ANT_TUNNEL_PARK = 105070001;
    private static final int STAIRWAY = 105100000;
    private static final long START = 1_000L;

    @Test
    void aCompanionStuckOnOneMapGivesTheTripUpAndFollowsItsOwnerAgain() {
        Character agent = companion(301, ANOTHER_ENTRANCE);
        Trip trip = travelling(agent, AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER, STAIRWAY);
        long limit = AgentSupplyRecoveryPolicy.travelNoProgressMs();

        assertTrue(AgentSupplyProcurementRuntime.tick(trip.entry, agent, START));
        assertTrue(AgentSupplyProcurementRuntime.tick(trip.entry, agent, START + limit - 1));
        assertEquals(AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER, trip.execution.phase());
        assertFalse(trip.execution.abandoned());

        assertTrue(AgentSupplyProcurementRuntime.tick(trip.entry, agent, START + limit));
        assertTrue(trip.execution.abandoned());
        assertTrue(trip.execution.abandonedReason().startsWith("no progress toward the supplier: " + limit
                + " ms on map " + ANOTHER_ENTRANCE + " without reaching its portal to map " + OTHER_MAP),
                trip.execution.abandonedReason());
        assertTrue(trip.execution.suppressed(AgentResourceCategory.HP_POTION, START + limit));

        // The ordinary abandon path: no walk back to the stairway, follow again beside the owner.
        assertFalse(AgentSupplyProcurementRuntime.tick(trip.entry, agent, START + limit + 1));
        assertFalse(trip.execution.isActive());
        assertNull(AgentObjectiveKernel.active(trip.entry));
        assertEquals(AgentObjectiveStatus.FAILED, lastStatus(trip));
        assertTrue(AgentModeStateRuntime.following(trip.entry));
        assertEquals(trip.leader, AgentRelationshipRuntime.followTarget(trip.entry));
    }

    @Test
    void crossingToAnotherMapStartsTheClockAgain() {
        Character agent = companion(302, ANOTHER_ENTRANCE);
        Trip trip = travelling(agent, AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER, STAIRWAY);
        long limit = AgentSupplyRecoveryPolicy.travelNoProgressMs();

        assertEquals(0L, trip.execution.travelStalledMs(ANOTHER_ENTRANCE, START));
        assertEquals(limit - 1, trip.execution.travelStalledMs(ANOTHER_ENTRANCE, START + limit - 1));
        assertEquals(0L, trip.execution.travelStalledMs(OTHER_MAP, START + limit - 1));
        assertEquals(1L, trip.execution.travelStalledMs(OTHER_MAP, START + limit));
    }

    @Test
    void everyPhaseChangeStartsTheClockAgain() {
        AgentSupplyProcurementState execution = new AgentSupplyProcurementState();
        execution.start("supply:HP_POTION:303", "maintenance:resupply:supply:HP_POTION:303",
                AgentResourceCategory.HP_POTION, ANT_TUNNEL_PARK, 1061001, STAIRWAY,
                AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER);
        assertEquals(0L, execution.travelStalledMs(ANOTHER_ENTRANCE, START));
        assertEquals(400_000L, execution.travelStalledMs(ANOTHER_ENTRANCE, START + 400_000L));

        execution.markAbandoned("no progress toward the supplier");
        assertEquals(0L, execution.travelStalledMs(ANOTHER_ENTRANCE, START + 500_000L));
        assertEquals(400_000L, execution.travelStalledMs(ANOTHER_ENTRANCE, START + 900_000L));

        execution.markReturning();
        assertEquals(0L, execution.travelStalledMs(ANOTHER_ENTRANCE, START + 1_000_000L));
        assertEquals(400_000L, execution.travelStalledMs(ANOTHER_ENTRANCE, START + 1_400_000L));

        execution.retryShopVisit(false, START);
        assertEquals(0L, execution.travelStalledMs(ANOTHER_ENTRANCE, START + 1_500_000L));
    }

    @Test
    void aRestockedAgentWhoseWalkBackStallsResumesItsPlanWhereItStands() {
        Character agent = companion(304, ANOTHER_ENTRANCE);
        Trip trip = travelling(agent, AgentSupplyProcurementState.Phase.RETURNING, OTHER_MAP);
        AgentRelationshipRuntime.setFollowTarget(trip.entry, null);
        long limit = AgentSupplyRecoveryPolicy.travelNoProgressMs();

        assertTrue(AgentSupplyProcurementRuntime.tick(trip.entry, agent, START));
        assertTrue(AgentSupplyProcurementRuntime.tick(trip.entry, agent, START + limit - 1));
        assertTrue(trip.execution.isActive());

        // It bought what it went for: the trip succeeded, with no cooldown and no party ask for potions it carries.
        assertFalse(AgentSupplyProcurementRuntime.tick(trip.entry, agent, START + limit));
        assertFalse(trip.execution.isActive());
        assertEquals(AgentObjectiveStatus.SUCCEEDED, lastStatus(trip));
        assertFalse(trip.execution.suppressed(AgentResourceCategory.HP_POTION, START + limit));
    }

    @Test
    void aGivenUpWalkBackThatStallsHandsThePlanBackWhereItStands() {
        Character agent = companion(305, ANOTHER_ENTRANCE);
        Trip trip = travelling(agent, AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER, OTHER_MAP);
        AgentRelationshipRuntime.setFollowTarget(trip.entry, null);
        trip.execution.markAbandoned("selected supplier NPC is unavailable");
        long limit = AgentSupplyRecoveryPolicy.travelNoProgressMs();

        assertTrue(AgentSupplyProcurementRuntime.tick(trip.entry, agent, START));
        assertTrue(AgentSupplyProcurementRuntime.tick(trip.entry, agent, START + limit - 1));
        assertTrue(trip.execution.isActive());

        assertFalse(AgentSupplyProcurementRuntime.tick(trip.entry, agent, START + limit));
        assertFalse(trip.execution.isActive());
        assertEquals(AgentObjectiveStatus.FAILED, lastStatus(trip));
    }

    private record Trip(AgentRuntimeEntry entry, AgentSupplyProcurementState execution, Character leader,
                        String maintenanceId) {
    }

    private static Character companion(int id, int mapId) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(id);
        when(agent.getName()).thenReturn("Watchdog" + id);
        when(agent.getMapId()).thenReturn(mapId);
        when(agent.getChair()).thenReturn(-1);
        return agent;
    }

    /** How the trip's maintenance objective ended (the last journal line for it). */
    private static AgentObjectiveStatus lastStatus(Trip trip) {
        List<AgentObjectiveJournalEntry> journal =
                trip.entry.capabilityStates().require(AgentObjectiveState.STATE_KEY).journalSnapshot();
        AgentObjectiveStatus status = null;
        for (AgentObjectiveJournalEntry line : journal) {
            if (line.objectiveId().equals(trip.maintenanceId)) {
                status = line.status();
            }
        }
        return status;
    }

    /**
     * A critical HP potion trip to Ant Tunnel Park, the Agent on {@code agent}'s map with no live map (no portal
     * resolves).
     */
    private static Trip travelling(Character agent, AgentSupplyProcurementState.Phase phase, int returnMapId) {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        Character leader = mock(Character.class);
        AgentRelationshipRuntime.setFollowTarget(entry, leader);
        String requestId = "supply:HP_POTION:" + agent.getId();
        AgentObjectiveDefinition maintenance = new AgentObjectiveDefinition(
                "maintenance:resupply:" + requestId, "maintenance.resupply", 1_000,
                Long.MAX_VALUE, 2, AgentObjectiveSource.RECOVERY_POLICY,
                "supply-procurement-v2", "maintenance:resupply:" + requestId);
        AgentObjectiveKernel.start(entry, maintenance, 10L);
        AgentResourcePlanningState planning = entry.capabilityStates().require(AgentResourcePlanningState.STATE_KEY);
        planning.update(new AgentSupplyNeed(AgentResourceCategory.HP_POTION, 0, 20, AgentSupplyUrgency.EMPTY, "", 10L),
                new AgentProcurementRequest(requestId, AgentResourceCategory.HP_POTION, 20, Long.MAX_VALUE,
                        List.of(AgentProcurementMethod.NPC_SHOP), AgentSupplyUrgency.EMPTY, "", Long.MAX_VALUE));
        AgentSupplyProcurementState execution = entry.capabilityStates().require(AgentSupplyProcurementState.STATE_KEY);
        execution.start(requestId, maintenance.objectiveId(), AgentResourceCategory.HP_POTION,
                ANT_TUNNEL_PARK, 1061001, returnMapId, phase);
        execution.rememberForegroundMode(true, false);
        return new Trip(entry, execution, leader, maintenance.objectiveId());
    }
}
