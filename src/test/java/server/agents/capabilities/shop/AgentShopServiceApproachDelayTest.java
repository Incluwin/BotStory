package server.agents.capabilities.shop;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.InventoryGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.scheduler.AgentSchedulerConfig;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A shop visit waits a random approach delay (up to SHOP_APPROACH_DELAY_MAX_MS) before the Agent sets off, and the
 * Agent holds still meanwhile. The delay used to count down one movement tick (50 ms) per visit tick, but an Agent
 * on a map no player is watching ticks only every background period (250 ms; its movement makes up for that with
 * substeps), so the delay ran five times longer against the wall-clock SHOP_VISIT_TIMEOUT_MS. Live 2026-09-21..10-06
 * (players=0): 145 visits to Glibber on El Nath Ice Valley II (211040200) timed out with "never reached the shop
 * NPC", 37 of them with the Agent standing at (259,-2074), 72 px from the approach point and inside SHOP_ARRIVE_DIST.
 */
class AgentShopServiceApproachDelayTest {
    private static final Point GLIBBER = new Point(363, -2077);
    private static final Point APPROACH = new Point(328, -2077);
    private static final Point BESIDE_GLIBBER = new Point(259, -2074);
    private static final int MAX_APPROACH_DELAY_MS = config.AgentTuning.intValue(
            "server.agents.capabilities.shop.AgentShopService.SHOP_APPROACH_DELAY_MAX_MS");
    private static final AgentSchedulerConfig SCHEDULER = AgentSchedulerConfig.fromSystemProperties();

    private final InventoryGateway inventory = mock(InventoryGateway.class);
    private Character bot;
    private AgentRuntimeEntry entry;
    private MockedStatic<AgentShopRuntime> speechAndSteps;

    @BeforeEach
    void anAgentStandingBesideGlibber() {
        bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.of());
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(211_040_200);
        when(bot.getName()).thenReturn("Vex892");
        when(bot.getPosition()).thenAnswer(inv -> new Point(BESIDE_GLIBBER));
        entry = new AgentRuntimeEntry(bot, null, null);
        // What the Agent says and the purchase steps an opened transaction schedules are not under test.
        speechAndSteps = mockStatic(AgentShopRuntime.class);
    }

    @AfterEach
    void closeStatics() {
        speechAndSteps.close();
    }

    @Test
    void anAgentNobodyWatchesStartsShoppingWhenItsApproachDelayRunsOut() {
        assertOpensWhenTheDelayRunsOut(SCHEDULER.backgroundActiveTickMs());
    }

    @Test
    void anAgentAPlayerWatchesStillWaitsItsWholeApproachDelay() {
        assertOpensWhenTheDelayRunsOut(SCHEDULER.baseTickMs());
    }

    /** Opens a visit with the longest approach delay and ticks it every {@code tickMs} until the transaction opens. */
    private void assertOpensWhenTheDelayRunsOut(long tickMs) {
        long start = 1_000_000L;
        AgentShopStateRuntime.startShopVisit(entry, GLIBBER, APPROACH, MAX_APPROACH_DELAY_MS, start);
        long now = start;
        long openedAfterMs = -1L;
        while (AgentShopStateRuntime.shopVisitPending(entry) && openedAfterMs < 0L) {
            AgentShopService.tickShopVisit(entry, bot, inventory, now);
            if (AgentShopStateRuntime.shopSequenceActive(entry)) {
                openedAfterMs = now - start;
            }
            now += tickMs;
        }

        assertTrue(openedAfterMs >= 0L, "the visit timed out with the Agent "
                + AgentShopApproachPolicy.manhattan(BESIDE_GLIBBER, APPROACH) + " px from the approach point");
        assertTrue(openedAfterMs >= MAX_APPROACH_DELAY_MS && openedAfterMs < MAX_APPROACH_DELAY_MS + tickMs,
                "ticking every " + tickMs + " ms, the transaction opened " + openedAfterMs
                        + " ms after a " + MAX_APPROACH_DELAY_MS + " ms approach delay began");
    }
}
