package server.agents.capabilities.partyquest.kpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentCombatCandidateClass;
import server.agents.capabilities.combat.AgentCombatDecisionReason;
import server.agents.capabilities.combat.AgentCombatDecisionStateRuntime;
import server.agents.capabilities.combat.AgentCombatDirective;
import server.agents.capabilities.combat.AgentCombatDirectiveRuntime;
import server.agents.capabilities.combat.AgentCombatVariationRuntime;
import server.agents.capabilities.looting.AgentGrindLootStateRuntime;
import server.agents.capabilities.movement.AgentFarmAnchorStateRuntime;
import server.agents.capabilities.movement.AgentGroundingService;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.catalog.AgentMapRegionAssignment;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.integration.cosmic.CosmicPrimitiveCapabilityGateway;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.life.SpawnPoint;

import java.awt.Point;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * Stage 1 helpers fight in the tower of the collector that needs the most coupons (pace plan C1). Live (run C) they
 * fought from a ground point under it instead: the point fell to the floor whenever it climbed a rope, every move
 * restarted their farming, and in the 200 s it worked alone they stood a median 370-410 px away. The combat directive
 * is the real one here: a territory that the next grind call dropped, or that changed on every tick, would fail.
 */
class StageOneHelperTerritoryTest {
    private static final int LIGATOR = 9_300_001;
    // Two spawns per tower; the west tower's x-band ends at -450, the middle one's at 200.
    private static final List<Point> SPAWNS = List.of(new Point(-900, -500), new Point(-800, -500),
            new Point(-100, -900), new Point(0, -900), new Point(400, -1000), new Point(450, -1000));

    private KpqRunFixture run;
    private MockedStatic<AgentModeService> modes;
    private MockedStatic<AgentModeStateRuntime> modeState;
    private MockedStatic<AgentFarmAnchorStateRuntime> farmAnchors;
    private MockedStatic<AgentCombatVariationRuntime> variation;
    private MockedStatic<AgentGrindLootStateRuntime> loot;
    private MockedStatic<AgentMovementStateRuntime> movement;
    private MockedStatic<AgentNavigationGraphService> graphs;
    private MockedStatic<AgentGroundingService> grounding;
    private final Set<AgentRuntimeEntry> grinding = new HashSet<>();
    private Character ben;
    private Character mira;
    private Character pip;
    private Character tobi;

    @BeforeEach
    void setUp() {
        run = new KpqRunFixture();
        modes = mockStatic(AgentModeService.class);
        modeState = mockStatic(AgentModeStateRuntime.class);
        farmAnchors = mockStatic(AgentFarmAnchorStateRuntime.class);
        variation = mockStatic(AgentCombatVariationRuntime.class);
        loot = mockStatic(AgentGrindLootStateRuntime.class);
        movement = mockStatic(AgentMovementStateRuntime.class);
        graphs = mockStatic(AgentNavigationGraphService.class);
        grounding = mockStatic(AgentGroundingService.class);
        modes.when(() -> AgentModeService.startGrind(any(), any()))
                .thenAnswer(call -> grinding.add(call.getArgument(0)));
        modeState.when(() -> AgentModeStateRuntime.grinding(any()))
                .thenAnswer(call -> grinding.contains(call.<AgentRuntimeEntry>getArgument(0)));
        movement.when(() -> AgentMovementStateRuntime.grounded(any())).thenReturn(true);
        // Each tower's platforms are one navigation region: 10 west, 20 middle, 30 east.
        AgentNavigationGraph graph = mock(AgentNavigationGraph.class);
        when(graph.findRegionId(any(), any())).thenAnswer(call -> {
            int x = call.<Point>getArgument(1).x;
            return x <= -450 ? 10 : x <= 200 ? 20 : 30;
        });
        graphs.when(() -> AgentNavigationGraphService.peekBestGraph(any(), any())).thenReturn(graph);
        List<SpawnPoint> spawns = SPAWNS.stream().map(position -> {
            SpawnPoint spawn = mock(SpawnPoint.class);
            when(spawn.getMonsterId()).thenReturn(LIGATOR);
            when(spawn.getPosition()).thenReturn(position);
            return spawn;
        }).toList();
        when(run.map.getMonsterSpawn()).thenReturn(spawns);
        ben = at(run.human(1, "Ben"), 0, 0);
        mira = at(run.companion(2, "Mira"), 0, 0);
        pip = at(run.companion(3, "Pip"), -50, -900);
        tobi = at(run.companion(4, "Tobi"), -850, -500);
    }

    @AfterEach
    void tearDown() {
        grounding.close();
        graphs.close();
        movement.close();
        loot.close();
        variation.close();
        farmAnchors.close();
        modeState.close();
        modes.close();
        run.close();
    }

    @Test
    void aHelperKeepsItsTerritoryAndTacticsWhileTheCollectorWorksItsTower() {
        AgentKpqSession session = miraHelpsWhilePipAndTobiCollect(7);

        AgentKpqCoordinator.tick(session, 2_000L);
        // Pip needs ten, Tobi three: Mira fights in Pip's middle tower.
        AgentMapRegionAssignment middle = territory(mira);
        assertNotNull(middle);
        assertEquals(List.of("20"), middle.regionIds());
        assertEquals(-449, middle.territoryMinX());
        assertEquals(200, middle.territoryMaxX());

        // Mira picks a Ligator, and Pip walks about its tower.
        AgentCombatDecisionStateRuntime.state(entry(mira)).tactical().selected(AgentKpqDefinition.STAGE_1_MAP,
                20, LIGATOR, AgentCombatCandidateClass.REQUIRED, AgentCombatDecisionReason.CLOSEST_ELIGIBLE, 2_500L);
        at(pip, 150, -900);
        AgentKpqCoordinator.tick(session, 3_000L);
        at(pip, -300, -900);
        AgentKpqCoordinator.tick(session, 4_000L);

        assertEquals(middle, territory(mira));
        assertEquals(LIGATOR, AgentCombatDirectiveRuntime.tacticalSnapshot(entry(mira)).lastSelectedMobId());
        modes.verify(() -> AgentModeService.startGrind(eq(entry(mira)), any()), times(1));
        modes.verify(() -> AgentModeService.startFarmHere(any(), any(), any()), never());
    }

    @Test
    void aHelperGoesToTheTowerOfTheCollectorThatNeedsTheMostCoupons() {
        AgentKpqSession session = miraHelpsWhilePipAndTobiCollect(0);
        when(pip.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)).thenReturn(7);

        AgentKpqCoordinator.tick(session, 2_000L);

        assertEquals(List.of("10"), territory(mira).regionIds());
    }

    @Test
    void theTerritoryStaysWhileTheCollectorClimbsOverAnotherTower() {
        AgentKpqSession session = miraHelpsWhilePipAndTobiCollect(7);
        AgentKpqCoordinator.tick(session, 2_000L);
        AgentMapRegionAssignment middle = territory(mira);

        // Pip is on the rope between the towers, already over the west tower's band.
        movement.when(() -> AgentMovementStateRuntime.grounded(entry(pip))).thenReturn(false);
        at(pip, -600, -700);
        AgentKpqCoordinator.tick(session, 3_000L);

        assertEquals(middle, territory(mira));
    }

    @Test
    void theTerritoryMovesOnlyOnceTheCollectorLandsInAnotherTower() {
        AgentKpqSession session = miraHelpsWhilePipAndTobiCollect(7);
        AgentKpqCoordinator.tick(session, 2_000L);

        at(pip, 420, -1000);
        AgentKpqCoordinator.tick(session, 3_000L);

        AgentMapRegionAssignment east = territory(mira);
        assertEquals(List.of("30"), east.regionIds());
        assertEquals(201, east.territoryMinX());
        // A new territory, but no restart of the fight.
        modes.verify(() -> AgentModeService.startGrind(eq(entry(mira)), any()), times(1));
    }

    @Test
    void helpersStayWithOneCollectorWhileTwoEqualNeedsTakeTurnsPickingUp() {
        AgentKpqSession session = miraHelpsWhilePipAndTobiCollect(0);

        // Both need ten, so Mira goes to Pip's tower; then the two keep passing each other by one coupon.
        AgentKpqCoordinator.tick(session, 2_000L);
        when(pip.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)).thenReturn(1);
        AgentKpqCoordinator.tick(session, 3_000L);
        when(tobi.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)).thenReturn(1);
        AgentKpqCoordinator.tick(session, 4_000L);
        when(pip.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)).thenReturn(2);
        AgentKpqCoordinator.tick(session, 5_000L);
        assertEquals(List.of("20"), territory(mira).regionIds());

        // Pip is done collecting, so Mira moves on to Tobi's west tower.
        session.member(3).markPassCreated();
        session.member(3).setRole(AgentKpqMemberState.Role.PASS_DELIVERER);
        AgentKpqCoordinator.tick(session, 6_000L);

        assertEquals(List.of("10"), territory(mira).regionIds());
    }

    @Test
    void aStockRunsHelpersStillFightFromASpotUnderTheCollector() {
        AgentKpqSession session = miraHelpsWhilePipAndTobiCollect(7, false);

        AgentKpqCoordinator.tick(session, 2_000L);

        assertNull(territory(mira));
        modes.verify(() -> AgentModeService.startFarmHere(eq(entry(mira)), eq(new Point(-50, -900)), any()));
    }

    @Test
    void theTerritoryEndsWithTheStage() {
        AgentKpqSession session = miraHelpsWhilePipAndTobiCollect(7);
        AgentKpqCoordinator.tick(session, 2_000L);

        when(run.event.getProperty("1stageclear")).thenReturn("true");
        AgentKpqCoordinator.tick(session, 3_000L);

        assertNotNull(AgentCombatDirectiveRuntime.directive(entry(mira)));
        assertNull(territory(mira));
    }

    @Test
    void theTerritoryEndsWithTheRun() {
        AgentKpqSession session = miraHelpsWhilePipAndTobiCollect(7);
        AgentKpqCoordinator.tick(session, 2_000L);
        // The release stops every member through the live gateway.
        PrimitiveCapabilityGateway released = AgentPrimitiveCapabilityGatewayRuntime.gateway();
        doAnswer(call -> {
            CosmicPrimitiveCapabilityGateway.INSTANCE.stop(call.getArgument(0));
            return null;
        }).when(released).stop(any());

        AgentKpqTerminationService.fail(session, "test", 3_000L);

        assertNull(territory(mira));
    }

    private AgentKpqSession miraHelpsWhilePipAndTobiCollect(int tobiHas) {
        return miraHelpsWhilePipAndTobiCollect(tobiHas, true);
    }

    /** Mira has handed in her pass and helps; Pip still needs ten coupons and Tobi {@code 10 - tobiHas}. */
    private AgentKpqSession miraHelpsWhilePipAndTobiCollect(int tobiHas, boolean companionRun) {
        AgentKpqSession session = run.session(companionRun, AgentKpqSession.Phase.STAGE_1, ben, mira, pip, tobi);
        collecting(session, mira, 0);
        session.member(2).markPassCreated();
        session.member(2).markPassDelivered();
        session.member(2).setRole(AgentKpqMemberState.Role.COMBAT_HELPER);
        collecting(session, pip, 0);
        collecting(session, tobi, tobiHas);
        return session;
    }

    private static AgentMapRegionAssignment territory(Character member) {
        AgentCombatDirective directive = AgentCombatDirectiveRuntime.directive(entry(member));
        return directive == null ? null : directive.regionAssignment();
    }

    private static AgentRuntimeEntry entry(Character member) {
        return AgentRuntimeRegistry.findByAgentCharacterId(member.getId());
    }

    private static Character at(Character member, int x, int y) {
        when(member.getPosition()).thenReturn(new Point(x, y));
        return member;
    }

    private static void collecting(AgentKpqSession session, Character companion, int have) {
        AgentKpqMemberState member = session.member(companion.getId());
        member.markQuestionRequested();
        member.setCouponTarget(10);
        member.setRole(AgentKpqMemberState.Role.COUPON_COLLECTOR);
        member.beginStageMovement(1, 0L);
        when(companion.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)).thenReturn(have);
    }
}
