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
import server.agents.capabilities.navigation.AgentNavigationRegionService;
import server.agents.integration.InventoryGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.scheduler.AgentSchedulerConfig;
import server.maps.MapleMap;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The visit timeout (SHOP_VISIT_TIMEOUT_MS, 30 s) was one flat limit from the moment the visit opened, approach delay
 * (up to SHOP_APPROACH_DELAY_MAX_MS) included, whatever the walk. Mushroom Shrine (800000000) puts its potion seller
 * (NPC 9110004) about 3,600 px from the spawn, roughly 29 s of walking at base speed. The visit opens on arrival,
 * while the Agent is still falling from the spawn point: with no current region, the approach point was picked
 * without the reachability check, on a platform beside the seller that no route reaches. Live 2026-09-21..10-06:
 * 5 timeouts at Mushroom Shrine with the Agent still standing where it landed, (279,155).
 */
class AgentShopServiceRouteTimeoutTest {
    private static final int MUSHROOM_SHRINE = 800_000_000;
    private static final Point POTION_SELLER = new Point(3943, 48);
    private static final Point SPAWN = new Point(279, 75);
    private static final Point LANDING = new Point(279, 155);
    private static final int MAX_APPROACH_DELAY_MS = config.AgentTuning.intValue(
            "server.agents.capabilities.shop.AgentShopService.SHOP_APPROACH_DELAY_MAX_MS");
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

    @BeforeEach
    void anAgentFallingFromTheMushroomShrineSpawn() {
        System.setProperty("wz-path", "wz");
        map = AgentNavigationMapLoader.loadMapGeometry(MUSHROOM_SHRINE);
        graph = AgentNavigationGraphService.getGraph(map, AgentMovementProfile.base());
        position = new Point(SPAWN);
        bot = mock(Character.class);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(MUSHROOM_SHRINE);
        when(bot.getName()).thenReturn("Kiri318");
        when(bot.getPosition()).thenAnswer(inv -> new Point(position));
        entry = new AgentRuntimeEntry(bot, null, null);
        AgentMovementStateRuntime.setMovementProfile(entry, AgentMovementProfile.base());
        AgentMovementStateRuntime.setInAir(entry, true);
        // What the Agent says and the purchase steps an opened transaction schedules are not under test.
        speechAndSteps = mockStatic(AgentShopRuntime.class);
    }

    @AfterEach
    void closeStatics() {
        speechAndSteps.close();
    }

    @Test
    void anAgentWalkingTheWholeWayAtBaseSpeedReachesTheMushroomShrinePotionSeller() {
        long start = 1_000_000L;
        AgentShopService.startShopVisit(entry, bot, POTION_SELLER, MAX_APPROACH_DELAY_MS, 0, 0, 0, start);
        Point approach = AgentShopStateRuntime.shopTargetPosition(entry);
        int landingRegion = graph.findRegionId(map, LANDING);
        int approachRegion = AgentNavigationRegionService.resolvePointTargetRegionId(graph, map, approach);
        assertTrue(landingRegion >= 0 && approachRegion >= 0 && graph.canReach(landingRegion, approachRegion),
                "no nav route from the landing " + LANDING + " to the approach point " + approach);

        // The Agent lands, waits out the approach delay, then walks straight there at base walking speed.
        double walkPxPerMs = AgentMovementProfile.base().walkVelocityPxs() / 1000.0;
        long walkMs = (long) Math.ceil(Math.abs(approach.x - LANDING.x) / walkPxPerMs);
        long now = start;
        long openedAfterMs = -1L;
        while (AgentShopStateRuntime.shopVisitPending(entry) && openedAfterMs < 0L) {
            long walkedMs = now - start - MAX_APPROACH_DELAY_MS;
            position = walkedMs <= 0L ? new Point(LANDING)
                    : walkedMs >= walkMs ? new Point(approach)
                    : new Point(LANDING.x + (int) Math.signum(approach.x - LANDING.x)
                            * (int) (walkedMs * walkPxPerMs), LANDING.y);
            AgentShopService.tickShopVisit(entry, bot, inventory, now);
            if (AgentShopStateRuntime.shopSequenceActive(entry)) {
                openedAfterMs = now - start;
            }
            now += TICK_MS;
        }

        assertTrue(openedAfterMs >= 0L, "the visit timed out " + (now - TICK_MS - start) + " ms in with the Agent at "
                + position + ", on a " + walkMs + " ms walk after a " + MAX_APPROACH_DELAY_MS + " ms approach delay");
    }

    @Test
    void anAgentThatNeverSetsOffStillGivesUp() {
        long start = 1_000_000L;
        AgentShopService.startShopVisit(entry, bot, POTION_SELLER, MAX_APPROACH_DELAY_MS, 0, 0, 0, start);
        position = new Point(LANDING);
        long now = start;
        while (AgentShopStateRuntime.shopVisitPending(entry) && now - start <= 4 * VISIT_TIMEOUT_MS) {
            AgentShopService.tickShopVisit(entry, bot, inventory, now);
            now += TICK_MS;
        }

        long gaveUpAfterMs = now - TICK_MS - start;
        assertTrue(!AgentShopStateRuntime.shopVisitPending(entry) && !AgentShopStateRuntime.shopSequenceActive(entry),
                "the Agent was still trying to reach the shop " + gaveUpAfterMs + " ms in");
        assertTrue(gaveUpAfterMs > MAX_APPROACH_DELAY_MS + VISIT_TIMEOUT_MS,
                "the visit gave up after " + gaveUpAfterMs + " ms, before the approach delay and the timeout had passed");
    }
}
