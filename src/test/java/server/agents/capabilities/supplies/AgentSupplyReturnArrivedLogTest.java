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
import testutil.LogLines;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A resupply whose walk back arrives says so in the log, once. Live, the citizen AngryWarrior shopped in
 * Kerning (103000002) at 07:11:18 and then logged nothing at all, so a live check could not tell a walk back that
 * arrived from one that never ended. Only a readiness restock logged its end ("readiness restock ... ended").
 */
class AgentSupplyReturnArrivedLogTest {
    private static final int ANOTHER_ENTRANCE = 105090200;
    private static final int DRAKE_FIELD = 105090300;
    private static final int ANT_TUNNEL_PARK = 105070001;
    private static final int ANT_TUNNEL_SHOPKEEPER = 1061001;
    private static final int BOUGHT = 50;
    private static final long START = 1_000L;

    @Test
    void aWalkBackThatArrivesLogsOneLineWhenItArrives() {
        Character agent = agent(501, ANOTHER_ENTRANCE);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        Trip trip = restockedTrip(entry, agent, DRAKE_FIELD, START);

        try (LogLines lines = new LogLines(AgentSupplyProcurementRuntime.class)) {
            // On its way back: still walking, nothing said yet.
            assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, START));
            assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, START + 1_000L));
            assertEquals(List.of(), lines.startingWith("Agent 'Supply501' walked back"));

            // It stands on the map it left: the trip ends as it did, and says so once.
            when(agent.getMapId()).thenReturn(DRAKE_FIELD);
            assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, START + 2_000L));
            assertFalse(trip.execution.isActive());
            assertEquals(AgentObjectiveStatus.SUCCEEDED, lastStatus(entry, trip));
            assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, START + 3_000L));
            assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, START + 4_000L));

            assertEquals(List.of("Agent 'Supply501' walked back to map " + DRAKE_FIELD
                    + " after its HP_POTION resupply"), lines.startingWith("Agent 'Supply501' walked back"));
            assertEquals(List.of(), lines.startingWith("Agent 'Supply501' is rejoining"));
        }
    }

    @Test
    void aWalkBackThatStartsOnTheMapItLeftLogsTheSameOneLine() {
        Character agent = agent(502, DRAKE_FIELD);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        Trip trip = restockedTrip(entry, agent, DRAKE_FIELD, START);

        try (LogLines lines = new LogLines(AgentSupplyProcurementRuntime.class)) {
            assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, START));
            assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, START + 1_000L));

            assertFalse(trip.execution.isActive());
            assertEquals(List.of("Agent 'Supply502' walked back to map " + DRAKE_FIELD
                    + " after its HP_POTION resupply"), lines.startingWith("Agent 'Supply502' walked back"));
        }
    }

    @Test
    void aCompanionThatGoesBackToItsOwnerLogsOneLineAndNoWalkBack() {
        Character agent = agent(503, ANT_TUNNEL_PARK);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        Character owner = mock(Character.class);
        AgentRelationshipRuntime.setFollowTarget(entry, owner);
        Trip trip = restockedTrip(entry, agent, DRAKE_FIELD, START);
        trip.execution.rememberForegroundMode(true, false);

        try (LogLines lines = new LogLines(AgentSupplyProcurementRuntime.class)) {
            assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, START));
            assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, START + 1_000L));

            assertFalse(trip.execution.isActive());
            assertEquals(AgentObjectiveStatus.SUCCEEDED, lastStatus(entry, trip));
            assertTrue(AgentModeStateRuntime.following(entry));
            assertEquals(List.of("Agent 'Supply503' is rejoining its owner after its HP_POTION resupply"),
                    lines.startingWith("Agent 'Supply503' is rejoining"));
            assertEquals(List.of(), lines.startingWith("Agent 'Supply503' walked back"));
        }
    }

    private record Trip(AgentRuntimeEntry entry, AgentSupplyProcurementState execution, String maintenanceId) {
    }

    private static Character agent(int id, int mapId) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(id);
        when(agent.getName()).thenReturn("Supply" + id);
        when(agent.getMapId()).thenReturn(mapId);
        when(agent.getChair()).thenReturn(-1);
        return agent;
    }

    /** A critical HP potion trip from Ant Tunnel Park's store whose shop leg restored the reserve: it walks back now. */
    private static Trip restockedTrip(AgentRuntimeEntry entry, Character agent, int returnMapId, long nowMs) {
        String requestId = "supply:HP_POTION:" + agent.getId() + ':' + nowMs;
        AgentObjectiveDefinition maintenance = new AgentObjectiveDefinition(
                "maintenance:resupply:" + requestId, "maintenance.resupply", 1_000,
                Long.MAX_VALUE, 2, AgentObjectiveSource.RECOVERY_POLICY,
                "supply-procurement-v2", "maintenance:resupply:" + requestId);
        AgentObjectiveKernel.start(entry, maintenance, nowMs);
        AgentResourcePlanningState planning = entry.capabilityStates().require(AgentResourcePlanningState.STATE_KEY);
        planning.update(new AgentSupplyNeed(AgentResourceCategory.HP_POTION, 0, 20, AgentSupplyUrgency.EMPTY, "", nowMs),
                new AgentProcurementRequest(requestId, AgentResourceCategory.HP_POTION, 20, Long.MAX_VALUE,
                        List.of(AgentProcurementMethod.NPC_SHOP), AgentSupplyUrgency.EMPTY, "", Long.MAX_VALUE));
        AgentSupplyProcurementState execution = entry.capabilityStates().require(AgentSupplyProcurementState.STATE_KEY);
        execution.start(requestId, maintenance.objectiveId(), AgentResourceCategory.HP_POTION,
                ANT_TUNNEL_PARK, ANT_TUNNEL_SHOPKEEPER, returnMapId, AgentSupplyProcurementState.Phase.SHOPPING);
        execution.rememberForegroundMode(false, false);
        execution.complete(new AgentSupplyProcurementOutcome(AgentSupplyProcurementOutcome.Status.RESTORED,
                AgentResourceCategory.HP_POTION, 0, BOUGHT, 5_000, 3_000, nowMs,
                "potion reserve restored to the restock target"));
        execution.markReturning();
        return new Trip(entry, execution, maintenance.objectiveId());
    }

    /** How the trip's maintenance objective ended (the last journal line for it). */
    private static AgentObjectiveStatus lastStatus(AgentRuntimeEntry entry, Trip trip) {
        List<AgentObjectiveJournalEntry> journal =
                entry.capabilityStates().require(AgentObjectiveState.STATE_KEY).journalSnapshot();
        AgentObjectiveStatus status = null;
        for (AgentObjectiveJournalEntry line : journal) {
            if (line.objectiveId().equals(trip.maintenanceId)) {
                status = line.status();
            }
        }
        return status;
    }
}
