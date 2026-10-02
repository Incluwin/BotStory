package server.agents.capabilities.partyquest.kpq;

import client.Character;
import client.inventory.WeaponType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentAttackExecutionProvider;
import server.agents.capabilities.combat.AgentAttackPlanScoringPolicy;
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
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.life.Monster;
import server.life.NPC;
import server.life.SpawnPoint;
import server.maps.MapItem;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * The stage-1 pace levers of a companion run led by a human (uptime1 L1, L2, L4, L6), each behind its own switch, and
 * proof that a stock run and an all-Agent run keep stage 1 as it was. The grind, territory and anchor calls go through
 * the coordinator's real gateway into the real combat directive; movement, grounding and the graph are stubbed with
 * four platforms of three Ligator spawns and the floor under them.
 */
class StageOneLeversTest {
    private static final int LIGATOR = 9_300_001;
    private static final long SWEEP_INTERVAL_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.COUPON_SWEEP_INTERVAL_MS");
    private static final long RESPAWN_WAIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_CLUSTER_RESPAWN_WAIT_MS");
    private static final long REPEAT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_LEADER_COUPON_REPEAT_MS");
    private static final long MAX_AGE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_LOOT_MAX_AGE_MS");
    private static final long SWEEP_AT = 1_000L + SWEEP_INTERVAL_MS;
    private static final String ASK = AgentKpqCoordinator.stageOneLeaderCouponAsk("Ben");
    private static final String ASK_AGAIN = AgentKpqCoordinator.stageOneLeaderCouponAskAgain("Ben");
    private static final AgentKpqStageOneLevers ALL = new AgentKpqStageOneLevers(true, true, true, true);

    /** A platform from minX to maxX standing at y, one navigation region. */
    private record Platform(int region, int minX, int maxX, int y) {
        boolean holds(int x) {
            return x >= minX && x <= maxX;
        }
    }

    // Clusters in the coordinator's order: west (10), middle (20), low by Cloto (40), east (30); the floor is 1.
    private static final List<Platform> PLATFORMS = List.of(new Platform(10, -950, -750, -500),
            new Platform(20, -150, 50, -900), new Platform(40, 250, 450, -300), new Platform(30, 350, 650, -1000));
    private static final List<Point> SPAWNS = List.of(
            new Point(-900, -500), new Point(-850, -500), new Point(-800, -500),
            new Point(-100, -900), new Point(-50, -900), new Point(0, -900),
            new Point(300, -300), new Point(350, -300), new Point(400, -300),
            new Point(400, -1000), new Point(450, -1000), new Point(500, -1000));

    private KpqRunFixture run;
    private MockedStatic<AgentModeService> modes;
    private MockedStatic<AgentModeStateRuntime> modeState;
    private MockedStatic<AgentFarmAnchorStateRuntime> farmAnchors;
    private MockedStatic<AgentCombatVariationRuntime> variation;
    private MockedStatic<AgentGrindLootStateRuntime> loot;
    private MockedStatic<AgentMovementStateRuntime> movement;
    private MockedStatic<AgentNavigationGraphService> graphs;
    private MockedStatic<AgentGroundingService> grounding;
    private MockedStatic<AgentAttackPlanScoringPolicy> scoring;
    private MockedStatic<AgentAttackExecutionProvider> weapons;
    private final Set<AgentRuntimeEntry> grinding = new HashSet<>();
    private final List<Monster> ligators = new ArrayList<>();
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
        scoring = mockStatic(AgentAttackPlanScoringPolicy.class);
        weapons = mockStatic(AgentAttackExecutionProvider.class, Mockito.CALLS_REAL_METHODS);
        modes.when(() -> AgentModeService.startGrind(any(), any()))
                .thenAnswer(call -> grinding.add(call.getArgument(0)));
        modes.when(() -> AgentModeService.startMoveTo(any(), any(), Mockito.anyBoolean()))
                .thenAnswer(call -> grinding.remove(call.<AgentRuntimeEntry>getArgument(0)));
        modeState.when(() -> AgentModeStateRuntime.grinding(any()))
                .thenAnswer(call -> grinding.contains(call.<AgentRuntimeEntry>getArgument(0)));
        movement.when(() -> AgentMovementStateRuntime.grounded(any())).thenReturn(true);
        AgentNavigationGraph graph = mock(AgentNavigationGraph.class);
        when(graph.findRegionId(any(), any())).thenAnswer(call -> region(call.getArgument(1)));
        graphs.when(() -> AgentNavigationGraphService.peekBestGraph(any(), any())).thenReturn(graph);
        grounding.when(() -> AgentGroundingService.findGroundPoint(any(), any()))
                .thenAnswer(call -> ground(call.getArgument(1)));
        weapons.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(any())).thenReturn(null);
        List<SpawnPoint> spawns = SPAWNS.stream().map(position -> {
            SpawnPoint spawn = mock(SpawnPoint.class);
            when(spawn.getMonsterId()).thenReturn(LIGATOR);
            when(spawn.getPosition()).thenReturn(position);
            return spawn;
        }).toList();
        when(run.map.getMonsterSpawn()).thenReturn(spawns);
        when(run.map.getAllMonsters()).thenAnswer(call -> List.copyOf(ligators));
        NPC cloto = mock(NPC.class);
        when(cloto.getPosition()).thenReturn(new Point(592, 106));
        when(run.map.getNPCById(AgentKpqDefinition.CLOTO_NPC)).thenReturn(cloto);
        ben = at(run.human(1, "Ben"), 560, 100);
        mira = at(run.companion(2, "Mira"), 400, -300);
        pip = at(run.companion(3, "Pip"), 450, -1000);
        tobi = at(run.companion(4, "Tobi"), 0, -900);
        hits(mira, 20.0);
        hits(pip, 50.0);
        hits(tobi, 30.0);
        // A Ligator on the west platform, which none of them camps: alive, so each line can be judged.
        ligator(-850, -500);
    }

    @AfterEach
    void tearDown() {
        weapons.close();
        scoring.close();
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

    // L1: platform clusters.

    @Test
    void everyCompanionCampsItsOwnClusterOnceAllHaveTheirTargets() {
        AgentKpqSession session = threeCollecting();

        try (KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class)) {
            AgentKpqCoordinator.tick(session, 2_000L);

            // Mira has the smallest target: the low cluster by Cloto. Pip hits hardest: of the rest the nearest
            // Cloto (all three spawns each). Tobi: the middle one.
            assertEquals(List.of("40"), territory(mira).regionIds());
            assertEquals(List.of("30"), territory(pip).regionIds());
            assertEquals(List.of("20"), territory(tobi).regionIds());
            assertEquals(RESPAWN_WAIT_MS, territory(pip).emptyBorrowDelayMs());
            assertEquals(1, log.startingWith("KPQ Stage 1 clusters: session=" + session.sessionId()).size());
            assertTrue(log.startingWith("KPQ Stage 1 clusters: ").getFirst()
                    .endsWith("assigned={Mira=2, Pip=3, Tobi=1}"));
        }
        // The territory stays equal from tick to tick, so nobody restarts its fight.
        AgentMapRegionAssignment camped = territory(pip);
        AgentKpqCoordinator.tick(session, 3_000L);
        assertEquals(camped, territory(pip));
        modes.verify(() -> AgentModeService.startGrind(eq(entry(pip)), any()), times(1));
    }

    @Test
    void untilEveryCompanionHasItsTargetTheCollectorsGrindMapWide() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, mira, pip, tobi);
        session.setStageOneLevers(ALL);
        collecting(session, mira, 5, 0);
        collecting(session, pip, 10, 2);
        // Tobi is still on his way to Cloto for his question.
        session.member(4).beginStageMovement(1, 0L);

        AgentKpqCoordinator.tick(session, 2_000L);

        assertFalse(session.stageOneClustersAssigned());
        assertNull(territory(pip));
        modes.verify(() -> AgentModeService.startGrind(eq(entry(pip)), any()));
    }

    @Test
    void aHolderCampsTheNeediestCollectorsClusterWhereverItWalks() {
        AgentKpqSession session = threeCollecting();
        holdingPass(session, mira);

        AgentKpqCoordinator.tick(session, 2_000L);
        // Pip needs eight more, Tobi five: Mira camps Pip's east cluster, not her own low one.
        assertEquals(List.of("30"), territory(mira).regionIds());

        at(pip, -50, -900);
        AgentKpqCoordinator.tick(session, 3_000L);

        assertEquals(List.of("30"), territory(mira).regionIds());
    }

    @Test
    void aCompanionKnockedOffItsClusterWalksBackWhileALigatorLivesThere() {
        AgentKpqSession session = threeCollecting();
        at(tobi, 0, 100);
        ligator(-50, -900);

        AgentKpqCoordinator.tick(session, 2_000L);

        modes.verify(() -> AgentModeService.startMoveTo(entry(tobi), new Point(0, -900), false));
        // Back home, it fights there again.
        at(tobi, 0, -900);
        AgentKpqCoordinator.tick(session, 3_000L);
        assertEquals(List.of("20"), territory(tobi).regionIds());
        modes.verify(() -> AgentModeService.startGrind(eq(entry(tobi)), any()));
    }

    @Test
    void withNothingAliveAtHomeItWaitsForTheRespawnWhereItIs() {
        AgentKpqSession session = threeCollecting();
        at(tobi, 0, 100);
        // A Ligator lives only in Pip's cluster.
        ligator(450, -1000);

        AgentKpqCoordinator.tick(session, 2_000L);

        modes.verify(() -> AgentModeService.startMoveTo(eq(entry(tobi)), any(), Mockito.anyBoolean()), never());
        assertEquals(List.of("20"), territory(tobi).regionIds());
    }

    @Test
    void withTheClusterLeverOffTheCollectorsGrindMapWide() {
        AgentKpqSession session = threeCollecting();
        session.setStageOneLevers(new AgentKpqStageOneLevers(true, true, true, false));

        AgentKpqCoordinator.tick(session, 2_000L);

        assertFalse(session.stageOneClustersAssigned());
        assertNull(territory(pip));
        assertNull(territory(tobi));
    }

    // L4: ranged firing spot.

    @Test
    void aRangedCompanionShootsFromASpotAStandoffFromItsClustersSpawns() {
        AgentKpqSession session = threeCollecting();
        shoots(pip);

        try (KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class)) {
            AgentKpqCoordinator.tick(session, 2_000L);
            AgentKpqCoordinator.tick(session, 3_000L);

            // East of the east platform's last spawn, the only spot 100 px clear of all three on that platform.
            modes.verify(() -> AgentModeService.startFarmHere(eq(entry(pip)), eq(new Point(600, -1000)), any()),
                    Mockito.atLeastOnce());
            assertEquals(List.of("KPQ Stage 1 firing spot: session=" + session.sessionId()
                            + " agent=Pip spot=600,-1000 area=[400,-1000, 450,-1000, 500,-1000]"),
                    log.startingWith("KPQ Stage 1 firing spot: "));
        }
        modes.verify(() -> AgentModeService.startFarmHere(eq(entry(mira)), any(), any()), never());
        modes.verify(() -> AgentModeService.startFarmHere(eq(entry(tobi)), any(), any()), never());
    }

    @Test
    void aRangedCollectorSentForACouponLeavesItsSpotAndKeepsTheCoupon() {
        AgentKpqSession session = threeCollecting();
        shoots(pip);
        MapItem coupon = coupon(31, 300, -1000, 0L);
        // Its fresh grind lets go of the objective, as the real restart does.
        loot.when(() -> AgentGrindLootStateRuntime.hasObjectiveLootTarget(entry(pip))).thenReturn(true, false);
        loot.when(() -> AgentGrindLootStateRuntime.grindLootTarget(entry(pip))).thenReturn(coupon);

        AgentKpqCoordinator.tick(session, 2_000L);

        modes.verify(() -> AgentModeService.startFarmHere(eq(entry(pip)), any(), any()), never());
        modes.verify(() -> AgentModeService.startGrind(eq(entry(pip)), any()));
        loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(entry(pip), coupon));
    }

    @Test
    void withTheStandoffLeverOffARangedCompanionGrindsItsCluster() {
        AgentKpqSession session = threeCollecting();
        session.setStageOneLevers(new AgentKpqStageOneLevers(true, true, false, true));
        shoots(pip);

        AgentKpqCoordinator.tick(session, 2_000L);

        modes.verify(() -> AgentModeService.startFarmHere(any(), any(), any()), never());
        assertEquals(List.of("30"), territory(pip).regionIds());
    }

    // L6: fight before loot.

    @Test
    void aCollectorFinishesTheLigatorOnItsPlatformBeforeAFarFreshCoupon() {
        AgentKpqSession session = miraAloneCollecting(0);
        ligator(350, -300);
        floor(coupon(31, -850, -500, SWEEP_AT - 1_000L));

        AgentKpqCoordinator.tick(session, SWEEP_AT);

        loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(eq(entry(mira)), any()), never());
    }

    @Test
    void aNearCouponIsTakenMidFight() {
        AgentKpqSession session = miraAloneCollecting(0);
        ligator(350, -300);
        MapItem near = coupon(31, 450, -300, SWEEP_AT - 1_000L);
        floor(near, coupon(32, -850, -500, SWEEP_AT - 1_000L));

        AgentKpqCoordinator.tick(session, SWEEP_AT);

        loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(entry(mira), near));
    }

    @Test
    void aCouponLeftLyingTooLongIsTakenMidFight() {
        AgentKpqSession session = miraAloneCollecting(0);
        ligator(350, -300);
        MapItem old = coupon(31, -850, -500, SWEEP_AT - MAX_AGE_MS);
        floor(old);

        AgentKpqCoordinator.tick(session, SWEEP_AT);

        loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(entry(mira), old));
    }

    @Test
    void couponsThatFinishItsCountAreTakenMidFight() {
        AgentKpqSession session = miraAloneCollecting(4);
        ligator(350, -300);
        MapItem last = coupon(31, -850, -500, SWEEP_AT - 1_000L);
        floor(last);

        AgentKpqCoordinator.tick(session, SWEEP_AT);

        loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(entry(mira), last));
    }

    @Test
    void aLigatorOnAnotherPlatformDoesNotHoldItBack() {
        AgentKpqSession session = miraAloneCollecting(0);
        ligator(450, -1000);
        MapItem far = coupon(31, -850, -500, SWEEP_AT - 1_000L);
        floor(far);

        AgentKpqCoordinator.tick(session, SWEEP_AT);

        loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(entry(mira), far));
    }

    @Test
    void withTheFightFirstLeverOffAFightingCollectorIsSentForAnyCoupon() {
        AgentKpqSession session = miraAloneCollecting(0);
        session.setStageOneLevers(new AgentKpqStageOneLevers(true, false, true, true));
        ligator(350, -300);
        MapItem far = coupon(31, -850, -500, SWEEP_AT - 1_000L);
        floor(far);

        AgentKpqCoordinator.tick(session, SWEEP_AT);

        loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(entry(mira), far));
    }

    // L2: the human leader's coupons.

    @Test
    void theNeediestCollectorAsksALeaderHoldingCouponsOnceAndOnceMoreLater() {
        AgentKpqSession session = threeCollecting();
        when(ben.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)).thenReturn(3);

        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, 3_000L);
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(pip, ASK), times(1));
        AgentKpqCoordinator.tick(session, 2_000L + REPEAT_MS - 1L);
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(ASK_AGAIN)), never());

        AgentKpqCoordinator.tick(session, 2_000L + REPEAT_MS);
        AgentKpqCoordinator.tick(session, 2_000L + 3 * REPEAT_MS);

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(pip, ASK_AGAIN), times(1));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(ASK)), times(1));
        assertEquals(2, session.stageOneLeaderCouponAsks());
    }

    @Test
    void aLeaderWithoutCouponsIsNotAsked() {
        AgentKpqSession session = threeCollecting();

        AgentKpqCoordinator.tick(session, 2_000L);

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(ASK)), never());
    }

    @Test
    void withTheLeaderCouponLeverOffNobodyAsks() {
        AgentKpqSession session = threeCollecting();
        session.setStageOneLevers(new AgentKpqStageOneLevers(false, true, true, true));
        when(ben.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)).thenReturn(3);

        AgentKpqCoordinator.tick(session, 2_000L);

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(ASK)), never());
    }

    @Test
    void aCouponTheLeaderDropsBesideTheFightingCollectorIsSweptEvenMidFight() {
        AgentKpqSession session = miraAloneCollecting(0);
        ligator(350, -300);
        MapItem dropped = coupon(31, 420, -300, SWEEP_AT - 500L);
        when(dropped.isPlayerDrop()).thenReturn(true);
        when(dropped.getOwnerId()).thenReturn(1);
        floor(dropped);

        AgentKpqCoordinator.tick(session, SWEEP_AT);

        loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(entry(mira), dropped));
    }

    @Test
    void theClearLogsTheLeadersKillsTheCouponsHeKeptAndTheAsks() {
        AgentKpqSession session = threeCollecting();
        when(ben.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)).thenReturn(3);
        MapItem hisKill = coupon(31, 560, 100, 1_500L);
        when(hisKill.getOwnerId()).thenReturn(1);
        MapItem hisNextKill = coupon(32, 560, 100, 1_600L);
        when(hisNextKill.getOwnerId()).thenReturn(1);
        MapItem miraKill = coupon(33, 400, -300, 1_600L);
        when(miraKill.getOwnerId()).thenReturn(2);
        MapItem hisDrop = coupon(34, 400, -300, 1_700L);
        when(hisDrop.getOwnerId()).thenReturn(1);
        when(hisDrop.isPlayerDrop()).thenReturn(true);

        try (KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class)) {
            floor(hisKill);
            AgentKpqCoordinator.tick(session, 2_000L);
            floor(hisKill, hisNextKill, miraKill, hisDrop);
            AgentKpqCoordinator.tick(session, 3_000L);
            when(run.event.getProperty("1stageclear")).thenReturn("true");
            AgentKpqCoordinator.tick(session, 4_000L);

            assertEquals(List.of("KPQ Stage 1 leader: session=" + session.sessionId()
                    + " leader=Ben kills=2 coupons=3 asked=1"), log.startingWith("KPQ Stage 1 leader: "));
        }
    }

    // Stock and all-Agent runs: no lever.

    @Test
    void anAllAgentRunKeepsStageOneAsItWas() {
        Character kai = at(run.companion(5, "Kai"), 560, 100);
        run.leaveParty(ben);
        run.leaveParty(tobi);
        run.leadParty(kai);
        AgentKpqSession session = run.session(false, AgentKpqSession.Phase.STAGE_1, kai, mira, pip);
        assertNoLeverActs(session, kai);
    }

    @Test
    void aCompanionRunAnAgentLeadsKeepsStageOneAsItWas() {
        Character kai = at(run.companion(5, "Kai"), 560, 100);
        run.leaveParty(ben);
        run.leaveParty(tobi);
        run.leadParty(kai);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, kai, mira, pip);
        assertNoLeverActs(session, kai);
    }

    @Test
    void aStockHumanLedRunKeepsStageOneAsItWas() {
        run.leaveParty(tobi);
        AgentKpqSession session = run.session(false, AgentKpqSession.Phase.STAGE_1, ben, mira, pip);
        try (KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class)) {
            assertNoLeverActs(session, ben);
            when(run.event.getProperty("1stageclear")).thenReturn("true");
            AgentKpqCoordinator.tick(session, SWEEP_AT + 2_000L);
            assertEquals(List.of(), log.startingWith("KPQ Stage 1 leader: "));
        }
    }

    /**
     * Mira, a ranged collector, fights a Ligator on her own platform with a far, fresh coupon on the floor; Pip is
     * collecting too, and the leader holds coupons. Stock stage 1: the sweep sends her for it, both grind map-wide,
     * nobody stands at a firing spot, nobody is given a cluster, and nobody asks the leader for anything.
     */
    private void assertNoLeverActs(AgentKpqSession session, Character leader) {
        collecting(session, mira, 5, 0);
        collecting(session, pip, 10, 2);
        shoots(mira);
        when(leader.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)).thenReturn(3);
        ligator(350, -300);
        MapItem far = coupon(31, -850, -500, SWEEP_AT - 1_000L);
        floor(far);

        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, SWEEP_AT);

        assertTrue(session.stageOneLevers().clusters(), "the switches are on; the run kind keeps them out");
        loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(eq(entry(mira)), eq(far)));
        assertFalse(session.stageOneClustersAssigned());
        assertNull(territory(mira));
        assertNull(territory(pip));
        modes.verify(() -> AgentModeService.startGrind(eq(entry(mira)), any()));
        modes.verify(() -> AgentModeService.startFarmHere(any(), any(), any()), never());
        modes.verify(() -> AgentModeService.startMoveTo(any(), any(), Mockito.anyBoolean()), never());
        String ask = AgentKpqCoordinator.stageOneLeaderCouponAsk(leader.getName());
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(ask)), never());
    }

    /** Ben leads; Mira needs five coupons, Pip eight more of ten and Tobi five more of ten, each on its platform. */
    private AgentKpqSession threeCollecting() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, mira, pip, tobi);
        session.setStageOneLevers(ALL);
        collecting(session, mira, 5, 0);
        collecting(session, pip, 10, 2);
        collecting(session, tobi, 10, 5);
        return session;
    }

    /** Ben leads; Mira is the only one still collecting, at her low platform; Pip holds its pass. */
    private AgentKpqSession miraAloneCollecting(int miraHas) {
        run.leaveParty(tobi);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, mira, pip);
        session.setStageOneLevers(ALL);
        collecting(session, mira, 5, miraHas);
        holdingPass(session, pip);
        return session;
    }

    private static void collecting(AgentKpqSession session, Character companion, int target, int have) {
        AgentKpqMemberState member = session.member(companion.getId());
        member.markQuestionRequested();
        member.setCouponTarget(target);
        member.setRole(AgentKpqMemberState.Role.COUPON_COLLECTOR);
        member.beginStageMovement(1, 0L);
        when(companion.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)).thenReturn(have);
    }

    private static void holdingPass(AgentKpqSession session, Character companion) {
        AgentKpqMemberState member = session.member(companion.getId());
        if (!member.questionRequested()) collecting(session, companion, 10, 0);
        member.markPassCreated();
        member.setRole(AgentKpqMemberState.Role.PASS_DELIVERER);
        when(companion.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false)).thenReturn(1);
    }

    private void shoots(Character companion) {
        weapons.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(companion)).thenReturn(WeaponType.BOW);
    }

    private void hits(Character companion, double lineDamage) {
        scoring.when(() -> AgentAttackPlanScoringPolicy.expectedLineDamage(eq(entry(companion)), any(), any()))
                .thenReturn(lineDamage);
    }

    private void ligator(int x, int y) {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(LIGATOR);
        when(monster.isAlive()).thenReturn(true);
        when(monster.getPosition()).thenReturn(new Point(x, y));
        ligators.add(monster);
    }

    private void floor(MapItem... drops) {
        when(run.map.getDroppedItems()).thenReturn(List.of(drops));
    }

    private static MapItem coupon(int objectId, int x, int y, long droppedAtMs) {
        MapItem drop = mock(MapItem.class);
        when(drop.getItemId()).thenReturn(AgentKpqDefinition.COUPON_ITEM);
        when(drop.getObjectId()).thenReturn(objectId);
        when(drop.getPosition()).thenReturn(new Point(x, y));
        when(drop.getDropTime()).thenReturn(droppedAtMs);
        return drop;
    }

    /** The region of a ground point (the gateway looks one pixel above it): its platform's, the floor's, or none. */
    private static int region(Point lookup) {
        for (Platform platform : PLATFORMS) {
            if (platform.holds(lookup.x) && Math.abs(lookup.y - platform.y()) <= 2) return platform.region();
        }
        return lookup.y >= 50 ? 1 : -1;
    }

    /** The first platform under a point, or the floor at y=100. */
    private static Point ground(Point point) {
        Platform under = null;
        for (Platform platform : PLATFORMS) {
            if (platform.holds(point.x) && platform.y() >= point.y
                    && (under == null || platform.y() < under.y())) {
                under = platform;
            }
        }
        return new Point(point.x, under == null ? 100 : under.y());
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
}
