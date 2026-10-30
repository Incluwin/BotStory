package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.movement.AgentStandaloneMoveTargetCoordinator;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.simulation.AgentSimulationMode;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * 2026-10-25 live (OPQ run 6): Vex (speed 115, jump 100) looped at the walkway's bottom rope for five minutes: up the
 * rope at x -49, off its top onto the short ledge (y -334, x -201..-5), right and off the ledge's end instead of the
 * jump up to the platform at (20..96, -396). Here the same climb runs on Map.wz with the real movement and graph, in the
 * background mode an unwatched room runs in (a tick moves five substeps). The rope exit's grounded hand-off steered at
 * the target on the platform above, and the five substeps walked her off the ledge before the next AI tick planned the
 * jump; with one substep (a watched map) she made it. Same harness, the old hand-off looped for all 400 ticks.
 */
class AgentOpqWalkwayLedgeTest {
    private final OpqRunFixture run = new OpqRunFixture();
    private final Character vex = run.companion(3, "Vex");
    private final AtomicReference<Point> position = new AtomicReference<>();

    @BeforeAll
    static void initWzPath() {
        System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void anUnwatchedClimberTakesTheJumpOffTheLedgeAboveTheBottomRope() {
        Point platform = new Point(60, -396);
        AgentRuntimeEntry real = walker(AgentNavigationMapLoader.loadMapGeometry(AgentOpqDefinition.WALKWAY_MAP),
                new Point(42, -127), 115, 100);

        int ticks = 0;
        for (; ticks < 400 && !onPlatform(real, platform); ticks++) { // 100 s of 250 ms ticks
            if (ticks % 100 == 0) run.clearInvocations(vex);
            boolean aiTick = ticks % 2 == 0;
            if (aiTick) AgentModeService.startMoveTo(real, platform, true); // as the grind's move does each AI tick
            AgentStandaloneMoveTargetCoordinator.tickStandaloneMoveTarget(real, vex, aiTick);
        }

        assertTrue(onPlatform(real, platform), "at " + position.get() + " after " + ticks + " ticks");
    }

    private boolean onPlatform(AgentRuntimeEntry real, Point platform) {
        Point at = position.get();
        return at.y == platform.y && at.x >= 20 && at.x <= 96 && AgentMovementStateRuntime.grounded(real);
    }

    /** Vex on {@code map} at {@code start} with the real movement and graph, in the background mode. */
    private AgentRuntimeEntry walker(MapleMap map, Point start, int speed, int jump) {
        AgentMovementProfile profile = new AgentMovementProfile(speed, jump);
        AgentNavigationGraphService.getGraph(map, profile);
        position.set(new Point(start));
        when(vex.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> { position.set(new Point(invocation.getArgument(0))); return null; })
                .when(vex).setPosition(any(Point.class));
        when(vex.getMap()).thenReturn(map);
        when(vex.getMapId()).thenReturn(map.getId());
        when(vex.getMaxHp()).thenReturn(3_000);
        when(vex.isAlive()).thenReturn(true);
        when(vex.getTotalMoveSpeedStat()).thenReturn(speed);
        when(vex.getTotalJumpStat()).thenReturn(jump);
        AgentRuntimeEntry real = new AgentRuntimeEntry(vex, null, null);
        AgentMovementStateRuntime.setMovementProfile(real, profile);
        real.simulationState().transitionTo(AgentSimulationMode.BACKGROUND_ACTIVE, 0L);
        var identity = run.identity; // the fixture mocks it for its own entries; the movement reads the real one
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(real)).thenCallRealMethod();
        identity.when(() -> AgentRuntimeIdentityRuntime.owner(real)).thenCallRealMethod();
        identity.when(() -> AgentRuntimeIdentityRuntime.botId(real)).thenCallRealMethod();
        identity.when(() -> AgentRuntimeIdentityRuntime.ownerId(real)).thenCallRealMethod();
        identity.when(() -> AgentRuntimeIdentityRuntime.botName(real)).thenCallRealMethod();
        identity.when(() -> AgentRuntimeIdentityRuntime.hasBot(real)).thenCallRealMethod();
        identity.when(() -> AgentRuntimeIdentityRuntime.botIs(eq(real), anyInt())).thenCallRealMethod();
        identity.when(() -> AgentRuntimeIdentityRuntime.botMapId(real)).thenCallRealMethod();
        identity.when(() -> AgentRuntimeIdentityRuntime.botMap(real)).thenCallRealMethod();
        identity.when(() -> AgentRuntimeIdentityRuntime.botHasMap(real)).thenCallRealMethod();
        identity.when(() -> AgentRuntimeIdentityRuntime.botPosition(real)).thenCallRealMethod();
        return real;
    }
}
