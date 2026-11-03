package server.agents.capabilities.shop;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.agents.capabilities.navigation.AgentNavigationPathService;
import server.agents.capabilities.navigation.AgentNavigationRegionService;
import server.agents.integration.InventoryGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.scheduler.AgentSchedulerConfig;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Ant Tunnel Park (105070001), the Dungeon supply trips' store: its potion seller (NPC 1061001) stands at (333,-249) on
 * a ledge up a rope at the top left, and an Agent coming up from the deeper tunnels arrives by the under00 portal at
 * the bottom left. The one route up runs east along the floor, up the rope column at x 1885-1986 and back west along
 * the top, about 38 s at base speed: past the first rope, 3 s in, the Agent stands no closer to the seller until it is
 * back west of x 1340 on the top. Live 2026-10-30..11-02: 57 visits failed "no closer in 30000 ms" with the Agent in
 * the column (42 of them at its top, x 1740-2015), and the retry from there reached the seller (52 of the 57).
 */
class AgentShopServiceAntTunnelParkTest {
    private static final int ANT_TUNNEL_PARK = 105_070_001;
    private static final Point POTION_SELLER = new Point(333, -249);
    private static final Point UNDER_PORTAL = new Point(-70, 951);
    /**
     * Live, the visits gave up 32-42 s in (an approach delay of up to 5 s included) with the Agent mostly at the top of
     * the column, which the route's estimate reaches 25 s in: the Agents walked it about a third to a half slower.
     */
    private static final double LIVE_PACE = 1.5;
    private static final long VISIT_TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.shop.AgentShopService.SHOP_VISIT_TIMEOUT_MS");
    private static final long TICK_MS = AgentSchedulerConfig.fromSystemProperties().backgroundActiveTickMs();

    private final InventoryGateway inventory = mock(InventoryGateway.class);
    private MapleMap map;
    private AgentNavigationGraph graph;
    private Character bot;
    private AgentRuntimeEntry entry;
    private Point position;
    private MockedStatic<AgentShopRuntime> speechAndSteps;

    /** One stretch of the walk: on the ground, or off it (no region) on a jump, a drop or a climb. */
    private record Leg(Point from, Point to, long ms, boolean grounded) {
        Point at(long intoMs) {
            double t = ms <= 0L ? 1.0 : Math.min(1.0, intoMs / (double) ms);
            return new Point((int) Math.round(from.x + t * (to.x - from.x)),
                    (int) Math.round(from.y + t * (to.y - from.y)));
        }
    }

    @BeforeEach
    void anAgentArrivedByTheUnderPortal() {
        System.setProperty("wz-path", "wz");
        map = AgentNavigationMapLoader.loadMapGeometry(ANT_TUNNEL_PARK);
        graph = AgentNavigationGraphService.getGraph(map, AgentMovementProfile.base());
        position = new Point(UNDER_PORTAL);
        bot = mock(Character.class);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(ANT_TUNNEL_PARK);
        when(bot.getName()).thenReturn("Kerua");
        when(bot.getPosition()).thenAnswer(inv -> new Point(position));
        entry = new AgentRuntimeEntry(bot, null, null);
        AgentMovementStateRuntime.setMovementProfile(entry, AgentMovementProfile.base());
        // What the Agent says and the purchase steps an opened transaction schedules are not under test.
        speechAndSteps = mockStatic(AgentShopRuntime.class);
    }

    @AfterEach
    void closeStatics() {
        speechAndSteps.close();
    }

    @Test
    void anAgentWalkingTheRouteUpTheColumnReachesThePotionSeller() {
        long start = 1_000_000L;
        AgentShopService.startShopVisit(entry, bot, POTION_SELLER, 0, 0, 0, 0, start);
        List<Leg> walk = walkToApproach();
        long walkMs = walk.stream().mapToLong(Leg::ms).sum();

        long now = start;
        long openedAfterMs = -1L;
        while (AgentShopStateRuntime.shopVisitPending(entry) && openedAfterMs < 0L) {
            walkTo(walk, now - start);
            AgentShopService.tickShopVisit(entry, bot, inventory, now);
            if (AgentShopStateRuntime.shopSequenceActive(entry)) {
                openedAfterMs = now - start;
            }
            now += TICK_MS;
        }

        assertTrue(openedAfterMs >= 0L, "the visit gave up " + (now - TICK_MS - start) + " ms in with the Agent at "
                + position + ", on a " + walkMs + " ms walk up the column");
    }

    @Test
    void anAgentThatStopsAtTheTopOfTheColumnStillGivesUp() {
        long start = 1_000_000L;
        AgentShopService.startShopVisit(entry, bot, POTION_SELLER, 0, 0, 0, 0, start);
        List<Leg> walk = walkToApproach();
        // it walks the route as far as the wall at x 1740 and stands there, as the live Agents were found
        Point stop = new Point(1782, -248);
        long stoppedAfterMs = 0L;
        List<Leg> shortWalk = new ArrayList<>();
        for (Leg leg : walk) {
            if (leg.grounded() && leg.from().x > stop.x && leg.to().x <= stop.x) {
                long toStopMs = Math.round(leg.ms() * (leg.from().x - stop.x) / (double) (leg.from().x - leg.to().x));
                shortWalk.add(new Leg(leg.from(), stop, toStopMs, true));
                stoppedAfterMs += toStopMs;
                break;
            }
            shortWalk.add(leg);
            stoppedAfterMs += leg.ms();
        }
        assertEquals(stop, shortWalk.get(shortWalk.size() - 1).to(), "the route crosses the top of the column");

        long now = start;
        while (AgentShopStateRuntime.shopVisitPending(entry) && now - start <= stoppedAfterMs + 4 * VISIT_TIMEOUT_MS) {
            walkTo(shortWalk, now - start);
            AgentShopService.tickShopVisit(entry, bot, inventory, now);
            now += TICK_MS;
        }

        long gaveUpAfterMs = now - TICK_MS - start;
        assertFalse(AgentShopStateRuntime.shopVisitPending(entry), "still trying " + gaveUpAfterMs + " ms in");
        assertTrue(gaveUpAfterMs <= stoppedAfterMs + VISIT_TIMEOUT_MS + 2 * TICK_MS, "it stood at " + stop
                + " from " + stoppedAfterMs + " ms, yet it gave up only " + gaveUpAfterMs + " ms in");
    }

    /** The nav route from the portal to the visit's approach point, each stretch LIVE_PACE times its estimate. */
    private List<Leg> walkToApproach() {
        Point approach = AgentShopStateRuntime.shopTargetPosition(entry);
        int fromRegion = graph.findRegionId(map, UNDER_PORTAL);
        int toRegion = AgentNavigationRegionService.resolvePointTargetRegionId(graph, map, approach);
        AgentNavigationPathService.SearchOutcome route = AgentNavigationPathService.findRouteForApproachProbe(
                graph, map, UNDER_PORTAL, fromRegion, toRegion, approach);
        assertEquals(AgentNavigationPathService.RouteCompleteness.COMPLETE, route.completeness(),
                "no nav route from the under portal to the approach point " + approach);
        assertTrue(route.path().stream().anyMatch(edge -> edge.startPoint.x > 1800 && edge.endPoint.y < -200),
                "the route no longer climbs the column: " + route.path().size() + " edges");

        List<Leg> legs = new ArrayList<>();
        Point at = UNDER_PORTAL;
        int region = fromRegion;
        for (AgentNavigationGraph.Edge edge : route.path()) {
            legs.add(new Leg(at, edge.startPoint, paced(AgentNavigationPathService.intraRegionTravelCost(
                    graph, region, at, edge.startPoint)), true));
            legs.add(new Leg(edge.startPoint, edge.endPoint, paced(edge.cost),
                    edge.type == AgentNavigationGraph.EdgeType.WALK));
            at = edge.endPoint;
            region = edge.toRegionId;
        }
        legs.add(new Leg(at, approach, paced(AgentNavigationPathService.intraRegionTravelCost(
                graph, region, at, approach)), true));
        return legs;
    }

    private static long paced(long estimateMs) {
        return Math.round(Math.max(0L, estimateMs) * LIVE_PACE);
    }

    /** Puts the Agent where the walk has it {@code walkedMs} in; off the ground on a jump, a drop or a rope. */
    private void walkTo(List<Leg> walk, long walkedMs) {
        long into = walkedMs;
        for (Leg leg : walk) {
            if (into < leg.ms()) {
                position = leg.at(into);
                AgentMovementStateRuntime.setInAir(entry, !leg.grounded());
                return;
            }
            into -= leg.ms();
        }
        position = new Point(walk.get(walk.size() - 1).to());
        AgentMovementStateRuntime.setInAir(entry, false);
    }
}
