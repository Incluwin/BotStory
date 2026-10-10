package server.agents.capabilities.supplies;

import client.Character;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.contracts.AgentProcurementMethod;
import server.agents.capabilities.contracts.AgentProcurementRequest;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.contracts.AgentSupplyNeed;
import server.agents.capabilities.contracts.AgentSupplyUrgency;
import server.agents.capabilities.navigation.AgentRouteOutcome;
import server.agents.capabilities.navigation.AgentRouteStatus;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRelationshipRuntime;
import server.agents.objectives.AgentObjectiveDefinition;
import server.agents.objectives.AgentObjectiveJournalEntry;
import server.agents.objectives.AgentObjectiveKernel;
import server.agents.objectives.AgentObjectiveSource;
import server.agents.objectives.AgentObjectiveState;
import server.agents.objectives.AgentObjectiveStatus;
import server.agents.progression.AgentVictoriaRouteRuntime;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * A resupply whose shop leg restocked the Agent but whose way back has no route. Review of fix/supply-trip-stuck: a
 * citizen on the Drake side whose walk back stalled on Another Entrance (105090200) has the hop to 105090300 blocked
 * for STALLED_HOP_BLOCK_MS. Put back on a Drake field within that time, its next critical trip still picks Ant Tunnel
 * Park (the way out is open), buys, and then finds no route back. That trip used to end as a route failure: a category
 * cooldown, "resumed its plan without restocking" and a party ask for potions it had just bought. The Balrog stairway
 * (105100000), which no route edge enters, ended every restocked trip from there the same way.
 */
class AgentSupplyReturnNoRouteTest {
    private static final int ANOTHER_ENTRANCE = 105090200;
    private static final int DRAKE_FIELD = 105090300;
    private static final int ANT_TUNNEL_PARK = 105070001;
    private static final int ANT_TUNNEL_SHOPKEEPER = 1061001;
    private static final int STAIRWAY = 105100000;
    private static final int BOUGHT = 50;
    private static final long START = 1_000L;
    private static final String NOT_RESTOCKED_LINE = "resumed its plan without restocking";

    @Test
    void aRestockedTripWithNoWayBackKeepsWhatItBoughtWhereItStands() {
        Character agent = agent(401, ANOTHER_ENTRANCE);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        Trip trip = restockedTrip(entry, agent, STAIRWAY, START);
        assertEquals(AgentRouteStatus.NO_ROUTE, AgentPrimitiveCapabilityGatewayRuntime.gateway().travelTo(
                entry, agent, STAIRWAY, START).status(), "no route edge enters the Balrog stairway");

        try (SupplyLogLines lines = new SupplyLogLines();
             MockedStatic<AgentPotionService> potions = mockStatic(AgentPotionService.class, CALLS_REAL_METHODS)) {
            assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, START));

            assertKeptWhatItBought(trip, START);
            potions.verify(() -> AgentPotionService.requestPotShare(any(), any(), anyBoolean(), anyBoolean()),
                    never());
            assertEquals(List.of("WARN Agent 'Supply401' stopped walking back to map " + STAIRWAY
                            + " after its HP_POTION resupply (restocked): no return route from map "
                            + ANOTHER_ENTRANCE), lines.atLeast(Level.WARN));
            assertTrue(lines.all().stream().noneMatch(line -> line.contains(NOT_RESTOCKED_LINE)), lines.all()::toString);
        }
    }

    @Test
    void aTripThatDidNotRestockAndHasNoWayBackEndsAsBeforeAndAsksItsParty() {
        Character agent = agent(402, ANOTHER_ENTRANCE);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        Trip trip = trip(entry, agent, AgentSupplyProcurementState.Phase.SHOPPING, STAIRWAY, START);
        trip.execution.markAbandoned("shop transaction ended in BLOCKED");

        try (SupplyLogLines lines = new SupplyLogLines();
             MockedStatic<AgentPotionService> potions = mockStatic(AgentPotionService.class, CALLS_REAL_METHODS)) {
            assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, START));

            assertFalse(trip.execution.isActive());
            assertEquals(AgentObjectiveStatus.FAILED, lastStatus(entry, trip));
            potions.verify(() -> AgentPotionService.requestPotShare(eq(entry), eq(agent), eq(true), eq(true)),
                    times(1));
            assertEquals(List.of("INFO Agent 'Supply402' " + NOT_RESTOCKED_LINE
                    + " HP_POTION and is asking the party instead"), lines.containing(NOT_RESTOCKED_LINE));
            assertTrue(lines.atLeast(Level.WARN).isEmpty(), lines.all()::toString);
        }
    }

    @Test
    void aTripThatFindsNoRouteToItsSupplierIsStillGivenUpWithACooldownAndAPartyAsk() {
        Character agent = agent(403, ANOTHER_ENTRANCE);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        Trip trip = trip(entry, agent, AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER, STAIRWAY, START);
        trip.execution.redirectSupplier(STAIRWAY, ANT_TUNNEL_SHOPKEEPER, -1);

        try (MockedStatic<AgentPotionService> potions = mockStatic(AgentPotionService.class, CALLS_REAL_METHODS)) {
            assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, START));
            assertTrue(trip.execution.abandoned());
            assertEquals("no portal route reaches the selected supplier", trip.execution.abandonedReason());
            assertEquals(AgentSupplyProcurementOutcome.Status.ROUTE_FAILED, trip.execution.lastOutcome().status());
            assertTrue(trip.execution.suppressed(AgentResourceCategory.HP_POTION, START));

            assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, START + 1));
            assertFalse(trip.execution.isActive());
            assertEquals(AgentObjectiveStatus.FAILED, lastStatus(entry, trip));
            potions.verify(() -> AgentPotionService.requestPotShare(eq(entry), eq(agent), eq(true), eq(true)),
                    times(1));
        }
    }

    @Test
    void aDrakeCitizenWhoseOwnStalledWalkBlockedTheWayBackKeepsItsNextTripsPotions() {
        Character agent = agent(404, ANOTHER_ENTRANCE);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        long limit = AgentSupplyRecoveryPolicy.travelNoProgressMs();

        // First trip: the walk back to the Drake field stands on Another Entrance until the watchdog ends it there.
        Trip first = restockedTrip(entry, agent, DRAKE_FIELD, START);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, START));
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, START + limit));
        assertEquals(AgentObjectiveStatus.SUCCEEDED, lastStatus(entry, first));

        // The hop it could not cross is blocked: the way out to Ant Tunnel Park stays open, the way back is gone.
        long later = START + limit + 60_000L;
        assertTrue(AgentVictoriaRouteRuntime.routeHops(entry, DRAKE_FIELD, ANT_TUNNEL_PARK, later) > 0);
        assertEquals(-1, AgentVictoriaRouteRuntime.routeHops(entry, ANT_TUNNEL_PARK, DRAKE_FIELD, later));

        // Back on a Drake field, its next critical trip buys at Ant Tunnel Park and finds no route home.
        when(agent.getMapId()).thenReturn(ANT_TUNNEL_PARK);
        Trip second = restockedTrip(entry, agent, DRAKE_FIELD, later);
        AgentRouteOutcome home = AgentPrimitiveCapabilityGatewayRuntime.gateway().travelTo(
                entry, agent, DRAKE_FIELD, later);
        assertEquals(AgentRouteStatus.NO_ROUTE, home.status());

        try (MockedStatic<AgentPotionService> potions = mockStatic(AgentPotionService.class, CALLS_REAL_METHODS)) {
            assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, later));

            assertKeptWhatItBought(second, later);
            potions.verify(() -> AgentPotionService.requestPotShare(any(), any(), anyBoolean(), anyBoolean()),
                    never());
        }
    }

    @Test
    void aRestockedCompanionStillWarpsBackToItsOwnerWhenNoRouteLeadsBack() {
        Character agent = agent(405, ANOTHER_ENTRANCE);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        Character owner = mock(Character.class);
        AgentRelationshipRuntime.setFollowTarget(entry, owner);
        Trip trip = restockedTrip(entry, agent, STAIRWAY, START);
        trip.execution.rememberForegroundMode(true, false);

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, START));

        assertKeptWhatItBought(trip, START);
        assertTrue(AgentModeStateRuntime.following(entry));
        assertEquals(owner, AgentRelationshipRuntime.followTarget(entry));
    }

    private static void assertKeptWhatItBought(Trip trip, long nowMs) {
        assertFalse(trip.execution.isActive());
        assertFalse(trip.execution.abandoned());
        assertEquals(AgentObjectiveStatus.SUCCEEDED, lastStatus(trip.entry, trip));
        // The shop leg's result stands: no route failure is written over it, and no cooldown blocks the next need.
        AgentSupplyProcurementOutcome outcome = trip.execution.lastOutcome();
        assertEquals(AgentSupplyProcurementOutcome.Status.RESTORED, outcome.status());
        assertEquals(BOUGHT, outcome.quantityAfter());
        assertFalse(trip.execution.suppressed(AgentResourceCategory.HP_POTION, nowMs));
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
        Trip trip = trip(entry, agent, AgentSupplyProcurementState.Phase.SHOPPING, returnMapId, nowMs);
        trip.execution.complete(new AgentSupplyProcurementOutcome(AgentSupplyProcurementOutcome.Status.RESTORED,
                AgentResourceCategory.HP_POTION, 0, BOUGHT, 5_000, 3_000, nowMs,
                "potion reserve restored to the restock target"));
        trip.execution.markReturning();
        return trip;
    }

    private static Trip trip(AgentRuntimeEntry entry,
                             Character agent,
                             AgentSupplyProcurementState.Phase phase,
                             int returnMapId,
                             long nowMs) {
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
                ANT_TUNNEL_PARK, ANT_TUNNEL_SHOPKEEPER, returnMapId, phase);
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

    /** The runtime's INFO and higher log lines, as "LEVEL message", while a test runs (the test logging is off). */
    private static final class SupplyLogLines implements AutoCloseable {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();
        private final LoggerContext context = LoggerContext.getContext(false);
        private final String loggerName = AgentSupplyProcurementRuntime.class.getName();
        private final AbstractAppender appender;

        SupplyLogLines() {
            appender = new AbstractAppender("supply-lines-" + loggerName, null, null, true, Property.EMPTY_ARRAY) {
                @Override
                public void append(LogEvent event) {
                    events.add(event.toImmutable());
                }
            };
            appender.start();
            LoggerConfig config = new LoggerConfig(loggerName, Level.INFO, false);
            config.addAppender(appender, Level.INFO, null);
            context.getConfiguration().addLogger(loggerName, config);
            context.updateLoggers();
        }

        List<String> all() {
            return events.stream().map(SupplyLogLines::line).toList();
        }

        List<String> atLeast(Level level) {
            return events.stream().filter(event -> event.getLevel().isMoreSpecificThan(level))
                    .map(SupplyLogLines::line).toList();
        }

        List<String> containing(String text) {
            return all().stream().filter(line -> line.contains(text)).toList();
        }

        private static String line(LogEvent event) {
            return event.getLevel() + " " + event.getMessage().getFormattedMessage();
        }

        @Override
        public void close() {
            context.getConfiguration().removeLogger(loggerName);
            context.updateLoggers();
            appender.stop();
        }
    }
}
