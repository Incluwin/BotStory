package server.agents.capabilities.supplies;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
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
    private static final int ARIANT = 260000000;
    private static final int ARIANT_SHOPKEEPER = 2100004;
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

    @Test
    void aTripThatShopsOnTheMapItLeftSaysItFinishedThereNotThatItWalkedBack() {
        // review 2026-10-10: a TOWN citizen in Ariant shopped at the town's own store (phase SHOPPING from the start)
        // and logged "walked back to map 260000000" though it never left the map
        Character agent = agent(504, ARIANT);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        Trip trip = restockedTrip(entry, agent, ARIANT, ARIANT_SHOPKEEPER, ARIANT, START);

        try (LogLines lines = new LogLines(AgentSupplyProcurementRuntime.class)) {
            assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, START));
            assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, START + 1_000L));

            assertFalse(trip.execution.isActive());
            assertEquals(AgentObjectiveStatus.SUCCEEDED, lastStatus(entry, trip));
            assertEquals(List.of(), lines.startingWith("Agent 'Supply504' walked back"));
            assertEquals(List.of("Agent 'Supply504' finished its HP_POTION resupply on map " + ARIANT),
                    lines.startingWith("Agent 'Supply504' finished"));
        }
    }

    @Test
    void aTripThatGaveUpAndWalksBackSaysItArrivedWithoutRestocking() {
        // a trip given up at its shop walked back and arrived; its only end line was "resumed its plan without
        // restocking", the same line as a trip given up whose walk back found no route, or one its owner called back
        Character agent = agent(505, ANOTHER_ENTRANCE);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        Trip trip = trip(entry, agent, ANT_TUNNEL_PARK, ANT_TUNNEL_SHOPKEEPER, DRAKE_FIELD, START);
        trip.execution.markAbandoned("shop transaction ended in BLOCKED");

        try (LogLines lines = new LogLines(AgentSupplyProcurementRuntime.class);
             MockedStatic<AgentPotionService> potions = mockStatic(AgentPotionService.class, CALLS_REAL_METHODS)) {
            assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, START)); // still walking back
            assertEquals(List.of(), lines.startingWith("Agent 'Supply505' walked back"));

            when(agent.getMapId()).thenReturn(DRAKE_FIELD);
            assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, START + 1_000L));

            assertFalse(trip.execution.isActive());
            assertEquals(AgentObjectiveStatus.FAILED, lastStatus(entry, trip));
            assertEquals(List.of("Agent 'Supply505' walked back to map " + DRAKE_FIELD
                    + " without restocking its HP_POTION"), lines.startingWith("Agent 'Supply505' walked back"));
            // it asks its party as before: one ask, with its line
            potions.verify(() -> AgentPotionService.requestPotShare(eq(entry), eq(agent), eq(true), eq(true)),
                    times(1));
            assertEquals(List.of("Agent 'Supply505' resumed its plan without restocking HP_POTION and is asking the"
                    + " party instead"), lines.startingWith("Agent 'Supply505' resumed"));
        }
    }

    @Test
    void aTripThatGaveUpOnTheMapItLeftSaysItFinishedThereWithoutRestocking() {
        Character agent = agent(506, ARIANT);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        Trip trip = trip(entry, agent, ARIANT, ARIANT_SHOPKEEPER, ARIANT, START);
        trip.execution.markAbandoned("shop transaction ended in BLOCKED");

        try (LogLines lines = new LogLines(AgentSupplyProcurementRuntime.class)) {
            assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, START));

            assertFalse(trip.execution.isActive());
            assertEquals(AgentObjectiveStatus.FAILED, lastStatus(entry, trip));
            assertEquals(List.of(), lines.startingWith("Agent 'Supply506' walked back"));
            assertEquals(List.of("Agent 'Supply506' finished its HP_POTION resupply on map " + ARIANT
                    + " without restocking"), lines.startingWith("Agent 'Supply506' finished"));
        }
    }

    @Test
    void aTripGivenUpBeforeItLeftItsMapSaysItFinishedThereWithoutRestocking() {
        // audit 2026-10-10: a trip toward another map's store given up before its first step (its request resolved:
        // a party member shared potions) logged "walked back to map 105090300" though it never left Drake field. A
        // route that fails at once, or a watchdog that fires on the map it left, ends the same way.
        Character agent = agent(507, DRAKE_FIELD);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        Trip trip = trip(entry, agent, ANT_TUNNEL_PARK, ANT_TUNNEL_SHOPKEEPER, DRAKE_FIELD,
                AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER, START);
        entry.capabilityStates().require(AgentResourcePlanningState.STATE_KEY).resolve(AgentResourceCategory.HP_POTION);

        try (LogLines lines = new LogLines(AgentSupplyProcurementRuntime.class)) {
            AgentSupplyProcurementRuntime.tick(entry, agent, START);
            AgentSupplyProcurementRuntime.tick(entry, agent, START + 1_000L);

            assertFalse(trip.execution.isActive());
            assertEquals(AgentObjectiveStatus.FAILED, lastStatus(entry, trip));
            assertEquals(List.of(), lines.startingWith("Agent 'Supply507' walked back"));
            assertEquals(List.of("Agent 'Supply507' finished its HP_POTION resupply on map " + DRAKE_FIELD
                    + " without restocking"), lines.startingWith("Agent 'Supply507' finished"));
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
        return restockedTrip(entry, agent, ANT_TUNNEL_PARK, ANT_TUNNEL_SHOPKEEPER, returnMapId, nowMs);
    }

    /** A critical HP potion trip from the given store whose shop leg restored the reserve: it walks back now. */
    private static Trip restockedTrip(AgentRuntimeEntry entry, Character agent, int supplierMapId, int supplierNpcId,
                                      int returnMapId, long nowMs) {
        Trip trip = trip(entry, agent, supplierMapId, supplierNpcId, returnMapId, nowMs);
        trip.execution.complete(new AgentSupplyProcurementOutcome(AgentSupplyProcurementOutcome.Status.RESTORED,
                AgentResourceCategory.HP_POTION, 0, BOUGHT, 5_000, 3_000, nowMs,
                "potion reserve restored to the restock target"));
        trip.execution.markReturning();
        return trip;
    }

    /** A critical HP potion trip at the given store, still at its shop. */
    private static Trip trip(AgentRuntimeEntry entry, Character agent, int supplierMapId, int supplierNpcId,
                             int returnMapId, long nowMs) {
        return trip(entry, agent, supplierMapId, supplierNpcId, returnMapId, AgentSupplyProcurementState.Phase.SHOPPING,
                nowMs);
    }

    /** A critical HP potion trip to the given store, in the given phase. */
    private static Trip trip(AgentRuntimeEntry entry, Character agent, int supplierMapId, int supplierNpcId,
                             int returnMapId, AgentSupplyProcurementState.Phase phase, long nowMs) {
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
                supplierMapId, supplierNpcId, returnMapId, phase);
        execution.rememberForegroundMode(false, false);
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
