package server.agents.capabilities.supplies;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.contracts.AgentProcurementMethod;
import server.agents.capabilities.contracts.AgentProcurementRequest;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.contracts.AgentSupplyNeed;
import server.agents.capabilities.contracts.AgentSupplyUrgency;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
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
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A resupply walk that stands on one map for TRAVEL_NO_PROGRESS_MS is ended. Live 2026-10-09: four companions on a
 * critical HP potion trip from the Balrog stairway stood at Another Entrance's arrival portal (105090200) from about
 * 01:58 to 02:06 and later, phase TRAVEL_TO_SUPPLIER, with no line logged. Most Agents here stand on 105090200 with no
 * live map, so no portal resolves (PORTAL_UNAVAILABLE) and the walk never leaves it. The live walk was MOVING every
 * tick instead (the portal resolved, navigation held the Agent in place): the Agents on the map's real geometry pin
 * that case.
 */
class AgentSupplyTravelWatchdogTest {
    private static final int ANOTHER_ENTRANCE = 105090200;
    private static final int OTHER_MAP = 105090100;
    private static final int ANT_TUNNEL_PARK = 105070001;
    private static final int STAIRWAY = 105100000;
    private static final long START = 1_000L;
    /** Where the temple's under00 portal sets an Agent down on 105090200's bottom floor (live 2026-10-09). */
    private static final Point UNDER00_LANDING = new Point(222, 1602);
    /** Where 105090200's west01 portal sets an Agent down on 105090100 (its east01 portal). */
    private static final Point EAST01_ARRIVAL = new Point(1873, 2213);

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
        assertTrue(trip.execution.lastOutcome().stalledTowardSupplier(),
                "its outcome says the watchdog gave the walk up (a benched companion's next slice restocks at the shop)");

        // The ordinary abandon path: no walk back to the stairway, follow again beside the owner.
        assertFalse(AgentSupplyProcurementRuntime.tick(trip.entry, agent, START + limit + 1));
        assertFalse(trip.execution.isActive());
        assertNull(AgentObjectiveKernel.active(trip.entry));
        assertEquals(AgentObjectiveStatus.FAILED, lastStatus(trip));
        assertTrue(AgentModeStateRuntime.following(trip.entry));
        assertEquals(trip.leader, AgentRelationshipRuntime.followTarget(trip.entry));
    }

    @Test
    void onlyAWalkToTheSupplierTheWatchdogGaveUpCountsAsStalledTowardIt() {
        assertTrue(outcome(AgentSupplyProcurementOutcome.Status.ROUTE_FAILED, "no progress toward the supplier: 300000 ms"
                + " on map 105040302 without reaching its portal to map 105040301").stalledTowardSupplier());
        assertFalse(outcome(AgentSupplyProcurementOutcome.Status.ROUTE_FAILED,
                "no portal route reaches the selected supplier").stalledTowardSupplier());
        assertFalse(outcome(AgentSupplyProcurementOutcome.Status.ROUTE_FAILED,
                "the route to the selected supplier needs 12 portal hops, more than the 10 the trip started with")
                .stalledTowardSupplier());
        assertFalse(outcome(AgentSupplyProcurementOutcome.Status.SHOP_FAILED,
                "selected supplier NPC is unavailable").stalledTowardSupplier());
        assertFalse(outcome(AgentSupplyProcurementOutcome.Status.INSUFFICIENT_MESO,
                "safe liquidation produced insufficient mesos for the required supply").stalledTowardSupplier());
    }

    private static AgentSupplyProcurementOutcome outcome(AgentSupplyProcurementOutcome.Status status, String reason) {
        return new AgentSupplyProcurementOutcome(status, AgentResourceCategory.HP_POTION, 0, 0, 1_000, 1_000, START, reason);
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
    void anObserverReadsTheWalksClockWithoutStartingIt() {
        // the bench dispatcher reads how long a walk has stood on its map when the walk's slice runs out of time
        AgentSupplyProcurementState execution = new AgentSupplyProcurementState();
        execution.start("supply:HP_POTION:307", "maintenance:resupply:supply:HP_POTION:307",
                AgentResourceCategory.HP_POTION, ANT_TUNNEL_PARK, 1061001, STAIRWAY,
                AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER);
        assertEquals(0L, execution.travelStoodMs(ANOTHER_ENTRANCE, START), "the walk has not looked yet");
        assertEquals(0L, execution.travelStalledMs(ANOTHER_ENTRANCE, START + 10_000L),
                "its own first look starts the clock, not the observer's read");

        assertEquals(170_000L, execution.travelStoodMs(ANOTHER_ENTRANCE, START + 180_000L));
        assertEquals(0L, execution.travelStoodMs(OTHER_MAP, START + 180_000L), "another map: not where it stands");
        assertEquals(180_000L, execution.travelStalledMs(ANOTHER_ENTRANCE, START + 190_000L),
                "reading another map left the walk's clock alone");
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

    @Test
    void itsNextTripsAvoidTheHopTheStalledWalkCouldNotCross() {
        Character agent = companion(306, ANOTHER_ENTRANCE);
        Trip trip = travelling(agent, AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER, STAIRWAY);
        long limit = AgentSupplyRecoveryPolicy.travelNoProgressMs();
        long blockMs = AgentSupplyRecoveryPolicy.stalledHopBlockMs();

        // Two refused-portal ticks only: the router's own block needs three failures, so a block here is the
        // watchdog's.
        assertTrue(AgentSupplyProcurementRuntime.tick(trip.entry, agent, START));
        assertTrue(AgentSupplyProcurementRuntime.tick(trip.entry, agent, START + limit));
        assertTrue(trip.execution.abandoned());

        // The next trip toward Ant Tunnel Park does not head for 105090100 again until the block has run out.
        AgentRouteOutcome during = AgentPrimitiveCapabilityGatewayRuntime.gateway().travelTo(
                trip.entry, agent, ANT_TUNNEL_PARK, START + limit + blockMs - 1);
        assertNotEquals(OTHER_MAP, during.nextMapId());
        AgentRouteOutcome after = AgentPrimitiveCapabilityGatewayRuntime.gateway().travelTo(
                trip.entry, agent, ANT_TUNNEL_PARK, START + limit + blockMs);
        assertEquals(OTHER_MAP, after.nextMapId());
    }

    @Test
    void aWalkBackThatStalledKeepsItsRouteOffThatHopToo() {
        Character agent = companion(307, ANOTHER_ENTRANCE);
        Trip trip = travelling(agent, AgentSupplyProcurementState.Phase.RETURNING, OTHER_MAP);
        AgentRelationshipRuntime.setFollowTarget(trip.entry, null);
        long limit = AgentSupplyRecoveryPolicy.travelNoProgressMs();

        assertTrue(AgentSupplyProcurementRuntime.tick(trip.entry, agent, START));
        assertFalse(AgentSupplyProcurementRuntime.tick(trip.entry, agent, START + limit));

        // The resumed plan heads for 105090100 by another way, or finds no route, instead of the same hold.
        AgentRouteOutcome next = AgentPrimitiveCapabilityGatewayRuntime.gateway().travelTo(
                trip.entry, agent, OTHER_MAP, START + limit + 1);
        assertNotEquals(OTHER_MAP, next.nextMapId());
    }

    @Test
    void aWalkThatKeepsMovingWithoutLeavingTheMapIsGivenUpTheSameWay() {
        Character agent = companionOnLiveMap(308, ANOTHER_ENTRANCE, UNDER00_LANDING);
        Trip trip = travelling(agent, AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER, STAIRWAY);
        long limit = AgentSupplyRecoveryPolicy.travelNoProgressMs();

        // As live: the portal to 105090100 resolves and the walk reports MOVING, but the Agent never gets there.
        AgentRouteOutcome walk = AgentPrimitiveCapabilityGatewayRuntime.gateway().travelTo(
                trip.entry, agent, ANT_TUNNEL_PARK, START);
        assertEquals(AgentRouteStatus.MOVING, walk.status());
        assertEquals(OTHER_MAP, walk.nextMapId());

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

        assertFalse(AgentSupplyProcurementRuntime.tick(trip.entry, agent, START + limit + 1));
        assertFalse(trip.execution.isActive());
        assertNull(AgentObjectiveKernel.active(trip.entry));
        assertEquals(AgentObjectiveStatus.FAILED, lastStatus(trip));
        assertTrue(AgentModeStateRuntime.following(trip.entry));
        assertEquals(trip.leader, AgentRelationshipRuntime.followTarget(trip.entry));

        // The hop it could not cross is blocked for its next trips, as for a walk whose portal never resolved.
        AgentRouteOutcome next = AgentPrimitiveCapabilityGatewayRuntime.gateway().travelTo(
                trip.entry, agent, ANT_TUNNEL_PARK, START + limit + 2);
        assertNotEquals(OTHER_MAP, next.nextMapId());
    }

    @Test
    void aMovingWalkThatCrossesToTheNextMapBeforeTheLimitKeepsGoing() {
        Character agent = companionOnLiveMap(309, ANOTHER_ENTRANCE, UNDER00_LANDING);
        Trip trip = travelling(agent, AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER, STAIRWAY);
        long limit = AgentSupplyRecoveryPolicy.travelNoProgressMs();
        long crossed = START + limit / 2;

        assertTrue(AgentSupplyProcurementRuntime.tick(trip.entry, agent, START));
        assertTrue(AgentSupplyProcurementRuntime.tick(trip.entry, agent, crossed - 1));
        standOnLiveMap(agent, OTHER_MAP, EAST01_ARRIVAL);
        AgentRouteOutcome walk = AgentPrimitiveCapabilityGatewayRuntime.gateway().travelTo(
                trip.entry, agent, ANT_TUNNEL_PARK, crossed);
        assertEquals(AgentRouteStatus.MOVING, walk.status());
        assertEquals(OTHER_MAP, walk.sourceMapId());

        // More than the limit since the trip left, less than the limit on this map: still walking.
        assertTrue(AgentSupplyProcurementRuntime.tick(trip.entry, agent, crossed));
        assertTrue(AgentSupplyProcurementRuntime.tick(trip.entry, agent, START + limit));
        assertTrue(AgentSupplyProcurementRuntime.tick(trip.entry, agent, crossed + limit - 1));
        assertEquals(AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER, trip.execution.phase());
        assertFalse(trip.execution.abandoned());

        // A full limit on the new map still ends it.
        assertTrue(AgentSupplyProcurementRuntime.tick(trip.entry, agent, crossed + limit));
        assertTrue(trip.execution.abandoned());
        assertTrue(trip.execution.abandonedReason().startsWith("no progress toward the supplier: " + limit
                + " ms on map " + OTHER_MAP + " "), trip.execution.abandonedReason());
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

    /**
     * A companion on {@code mapId}'s real geometry at {@code position}, far from every portal: the router resolves
     * the portal and sends it walking (MOVING), and a mocked Agent never moves.
     */
    private static Character companionOnLiveMap(int id, int mapId, Point position) {
        Character agent = companion(id, mapId);
        standOnLiveMap(agent, mapId, position);
        return agent;
    }

    private static void standOnLiveMap(Character agent, int mapId, Point position) {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(mapId);
        when(agent.getMapId()).thenReturn(mapId);
        when(agent.getMap()).thenReturn(map);
        when(agent.getPosition()).thenReturn(new Point(position));
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
