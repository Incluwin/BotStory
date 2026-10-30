package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentMovementPoseService;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.movement.AgentMovementStateResetService;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.movement.AgentStandaloneMoveTargetCoordinator;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.agents.capabilities.navigation.AgentPortalApproachService;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.simulation.AgentSimulationMode;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-10-25 live (OPQ run 5): the way up's runner (Kettle) and the lounge's (Jinx) never got past their first row of
 * trial doors in 30 minutes. A right door warped the runner up a row, but its move target was still the door it had
 * just used, so it dropped back to it, and the read 650 ms later took the right door for a wrong one; the hop it had
 * landed on the door with was charged as a missed arc too, and three of those shut the hop for 30 s. Here the
 * coordinator's runner climbs Map.wz's rows with the real movement, through doors that do what party3_r6pt.js and
 * party3_r4pt.js do (a right door up a row, a wrong one down to r6fail or the lounge's fail point). Same harness,
 * the old code: the way up solved no row in 30 minutes, the lounge reached its landing in 4 of 10 combinations.
 */
class AgentOpqTrialDoorsTest {
    private static final int WAY_UP_SPEED = 123; // Kettle with Wonky's blessing (speed +20, jump +10)
    private static final int WAY_UP_JUMP = 110;

    private final OpqRunFixture run = new OpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character kes = run.companion(3, "Kes");
    private final Character[] others = {run.companion(2, "Mira"), run.companion(4, "Pel"), run.companion(5, "Hal"),
            run.companion(6, "Gus")};
    private final AtomicReference<Point> position = new AtomicReference<>();
    private AgentOpqSession session;
    private AgentRuntimeEntry entry;

    @BeforeAll
    static void initWzPath() {
        System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void theWayUpRunnerSolvesEveryRowAndReachesTheTop() {
        int[] rightColumn = {0, 3, 1, 4, 2, 2, 4, 1, 3, 4, 1, 1, 2, 3, 4, 2, 3}; // by script row 1-16
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(AgentOpqDefinition.ON_WAY_UP_MAP);
        AgentRuntimeEntry real = drive(map, new Point(-50, -1_865), WAY_UP_SPEED, WAY_UP_JUMP,
                AgentOpqMemberState.Role.WAY_UP_RUNNER, AgentOpqDefinition.Room.ON_WAY_UP, 0);
        doors(map, real, portal -> {
            int id = Integer.parseInt(portal.getName().substring(2, 5)); // rp011..rp164: row, column
            int row = id / 10;
            if (id % 10 != rightColumn[row]) return (row - 1) / 4 > 1 ? (row - 1) / 4 : 5;
            return row % 4 != 0 ? portal.getId() + 4 : row / 4;
        });

        int ticks = tick(real, 2_400, () -> position.get().y < -4_900); // ten minutes of 250 ms ticks

        assertTrue(position.get().y < -4_900, "stuck at " + position.get() + " after " + ticks + " ticks");
        for (int row = 0; row < 16; row++) assertTrue(session.wayUpRoute().solved(row), "row " + row);
    }

    @Test
    void theLoungeRunnerReachesTheHighLandingPastTwoWrongDoorsInEachRow() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(AgentOpqDefinition.LOUNGE_MAP);
        AgentRuntimeEntry real = drive(map, new Point(-57, -1_265), 140, 130,
                AgentOpqMemberState.Role.LOUNGE_RUNNER, AgentOpqDefinition.Room.LOUNGE, 920_010_602);
        doors(map, real, portal -> {
            boolean secondRow = portal.getId() >= 14; // r4rp11-13, r4rp21-23; the third of each is right
            if (Integer.parseInt(portal.getName().substring(4, 6)) % 10 != 3) return 2;
            return secondRow ? 1 : portal.getId() + 3;
        });

        int ticks = tick(real, 600, () -> position.get().y < -1_760);

        assertTrue(position.get().y < -1_760, "stuck at " + position.get() + " after " + ticks + " ticks");
        assertTrue(session.loungeRoute().solved(0) && session.loungeRoute().solved(1));
    }

    /**
     * 2026-10-25 live (run 6): Jinx (speed 140, jump 119 with Haste), given 920010601, went through the trial doors for
     * 33 minutes and never entered it. Its door (in00, y -1266) stands on the floor below the doors; past them, her way
     * back to it led down to that floor and into the doors again. Same harness, the old coordinator never entered it in
     * 1,200 ticks.
     */
    @Test
    void theLoungeRunnerGivenTheFirstSubroomTakesItsDoorBelowTheTrialDoors() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(AgentOpqDefinition.LOUNGE_MAP);
        AgentRuntimeEntry real = drive(map, new Point(-45, -728), 140, 119,
                AgentOpqMemberState.Role.LOUNGE_RUNNER, AgentOpqDefinition.Room.LOUNGE, 920_010_601);
        AtomicBoolean throughADoor = new AtomicBoolean();
        doors(map, real, portal -> { // run 6's doors: the first of each row is right
            throughADoor.set(true);
            if (Integer.parseInt(portal.getName().substring(4, 6)) % 10 != 1) return 2;
            return portal.getId() >= 14 ? 1 : portal.getId() + 3;
        });
        AtomicBoolean entered = new AtomicBoolean();
        doAnswer(invocation -> { entered.set(true); return true; }).when(run.partyQuest).enterPortal(kes, 3); // in00

        int ticks = tick(real, 600, entered::get);

        assertTrue(entered.get(), "never entered 920010601; at " + position.get() + " after " + ticks + " ticks");
        assertFalse(throughADoor.get(), "went through the trial doors on the way to 920010601");
    }

    @Test
    void aRightDoorIsLearnedWhereItLandsAndTheRunnerStopsThere() {
        Point door = new Point(11, -2_093);
        mockedRunner(door, true);
        when(run.partyQuest.enterPortal(kes, 24)).thenAnswer(invocation -> {
            position.set(new Point(6, -2_213)); // rp023, a row up
            return true;
        });

        AgentOpqCoordinator.tickMember(session, entry, kes, 10_000L);

        assertTrue(session.wayUpRoute().solved(0));
        verify(run.actions).stop(entry);
    }

    @Test
    void theRunnerTakesNoDoorInTheAir() {
        Point door = new Point(11, -2_093);
        mockedRunner(door, false);

        AgentOpqCoordinator.tickMember(session, entry, kes, 10_000L);

        verify(run.partyQuest, never()).enterPortal(any(Character.class), anyInt());
        verify(run.actions).navigate(entry, door, true);
        assertFalse(session.wayUpRoute().solved(0));
    }

    /** Kes at the way up's first door (row 0, its first trial), standing or in the air, with mocked movement. */
    private void mockedRunner(Point door, boolean grounded) {
        position.set(new Point(door.x + 4, door.y - 20));
        when(kes.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        when(kes.getMapId()).thenReturn(AgentOpqDefinition.ON_WAY_UP_MAP);
        when(run.actions.portalPosition(kes, 24)).thenReturn(door);
        when(run.actions.grounded(kes)).thenReturn(grounded);
        start(AgentOpqMemberState.Role.WAY_UP_RUNNER, AgentOpqDefinition.Room.ON_WAY_UP, 0);
    }

    private void start(AgentOpqMemberState.Role role, AgentOpqDefinition.Room room, int subroom) {
        session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, true, AgentOpqSession.Phase.SPLIT_ROOMS,
                ben, others[0], kes, others[1], others[2], others[3]);
        session.member(kes.getId()).assign(role, room, subroom);
        entry = run.entries.get(kes.getId());
    }

    /** Kes on {@code map} at {@code start} with the real movement and graph; the coordinator's moves drive it. */
    private AgentRuntimeEntry drive(MapleMap map, Point start, int speed, int jump, AgentOpqMemberState.Role role,
                                    AgentOpqDefinition.Room room, int subroom) {
        AgentMovementProfile profile = new AgentMovementProfile(speed, jump);
        AgentNavigationGraphService.getGraph(map, profile);
        position.set(new Point(start));
        when(kes.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> { position.set(new Point(invocation.getArgument(0))); return null; })
                .when(kes).setPosition(any(Point.class));
        when(kes.getMap()).thenReturn(map);
        when(kes.getMapId()).thenReturn(map.getId());
        when(kes.getMaxHp()).thenReturn(3_000);
        when(kes.isAlive()).thenReturn(true);
        when(kes.getTotalMoveSpeedStat()).thenReturn(speed);
        when(kes.getTotalJumpStat()).thenReturn(jump);
        AgentRuntimeEntry real = new AgentRuntimeEntry(kes, null, null);
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
        start(role, room, subroom);
        when(run.actions.portalPosition(eq(kes), anyInt())).thenAnswer(invocation ->
                AgentPortalApproachService.navigableTarget(map, map.getPortal((int) invocation.getArgument(1))));
        when(run.actions.grounded(kes)).thenAnswer(invocation -> AgentMovementStateRuntime.grounded(real));
        doAnswer(invocation -> { AgentModeService.startMoveTo(real, invocation.getArgument(1), true); return null; })
                .when(run.actions).navigate(eq(entry), any(Point.class), anyBoolean());
        doAnswer(invocation -> { AgentModeService.startStop(real); return null; }).when(run.actions).stop(entry);
        return real;
    }

    /** The trial doors warp as CosmicMapGateway.enterPortal does, to the portal {@code destination} names. */
    private void doors(MapleMap map, AgentRuntimeEntry real, java.util.function.ToIntFunction<Portal> destination) {
        when(run.partyQuest.enterPortal(eq(kes), anyInt())).thenAnswer(invocation -> {
            Point to = new Point(map.getPortal(destination.applyAsInt(map.getPortal((int) invocation.getArgument(1))))
                    .getPosition());
            kes.setPosition(to);
            AgentMovementPoseService.teleportTo(real, kes, new Point(to));
            AgentMovementStateResetService.resetEntryStateAfterTeleport(real);
            return true;
        });
    }

    /** Coordinator passes on every AI tick (every other 250 ms movement tick) until {@code done}; the ticks run. */
    private int tick(AgentRuntimeEntry real, int maxTicks, java.util.function.BooleanSupplier done) {
        long now = 10_000L;
        int ticks = 0;
        for (; ticks < maxTicks && !done.getAsBoolean(); ticks++) {
            if (ticks % 100 == 0) run.clearInvocations(kes);
            boolean aiTick = ticks % 2 == 0;
            if (aiTick) AgentOpqCoordinator.tickMember(session, entry, kes, now);
            AgentStandaloneMoveTargetCoordinator.tickStandaloneMoveTarget(real, kes, aiTick);
            now += 250L;
        }
        return ticks;
    }
}
