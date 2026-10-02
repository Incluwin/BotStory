package server.agents.capabilities.partyquest.kpq;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentAttackPlanScoringPolicy;
import server.agents.capabilities.combat.AgentCombatObjectiveTargetStateRuntime;
import server.agents.capabilities.combat.AgentCombatVariationRuntime;
import server.agents.capabilities.looting.AgentGrindLootStateRuntime;
import server.agents.capabilities.looting.AgentGrindLootTargetService;
import server.agents.capabilities.movement.AgentFarmAnchorStateRuntime;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.agents.capabilities.navigation.AgentNavigationRegionService;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.plans.AgentScriptItemActionService;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.life.NPC;

import java.awt.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage-5 passes a companion run's human leader leaves lying at his feet. Live (2026-10-02, session kpq-a6f696f0)
 * Faro723 and SilentSin dropped four and six passes beside him (dx=-6); he never picked any up, and as the passes lay in
 * his reach the stale-pass rule never let the missing-pass recovery run, which came only after they expired 180 s later
 * (stage 5's carry took 229 s against 35 s). Now he hears about them once, and past the in-reach patience they stop
 * holding the recovery back, which then makes the ones still lying vanish. Every pass drop logs where it landed.
 *
 * <p>Drops here land on the floor as the map's own would: player drops owned by the dropper, stamped with the drop's
 * time. A pass a kill left lying is owned by the killer too, but is no player drop.
 */
class StageFivePassPatienceTest {
    private static final Point BEN_BY_CLOTO = new Point(260, -2717);
    private static final Point AT_HIS_FEET = new Point(254, -2717);
    private static final Point CLOTO = new Point(305, -2717);
    private static final Point FAR_BELOW = new Point(0, -451);
    private static final int LEDGE = 5;
    private static final int CLOTOS_ISLAND = 6;
    private static final int BELOW = 19;
    private static final long NUDGE_MS = tuning("STAGE5_PASS_NUDGE_MS");
    private static final long PATIENCE_MS = tuning("STAGE5_INREACH_PASS_PATIENCE_MS");
    private static final long STALE_MS = tuning("STAGE5_FLOOR_PASS_PATIENCE_MS");
    private static final long GRACE_MS = tuning("STAGE5_MISSING_PASS_GRACE_MS");
    private static final long HANDOVER_MS = tuning("STAGE5_PASS_HANDOVER_MS");
    // King Slime's revive grace runs out at 4 s in these runs; nothing is left to fetch, so they set out then.
    private static final long SET_OUT_MS = 4_100L;
    private static final long DROPPED_AT_MS = 5_000L;
    private static final String NUDGE = "Ben, the passes are right by your feet.";
    private static final String NUDGE_ONE = "Ben, the pass is right by your feet.";
    private static final String NUDGE_BACK_THERE = "Ben, you left the passes on the ground back there.";
    private static final List<String> NUDGES = List.of(NUDGE, NUDGE_ONE, NUDGE_BACK_THERE,
            "Ben, you left a pass on the ground back there.");
    private static final String RECOLLECT = "i'll go grab the passes still lying around.";
    private static final String RECOLLECTED = "Ben, got the rest of the passes, they're at your feet.";
    private static final String RECOVERY = "KPQ Stage 5 missing-pass recovery restored inventory only: ";
    private static final String TAKEN_BACK = "KPQ Stage 5 passes taken back at the recovery: ";
    private static final Pattern ENGINE = Pattern.compile("->|\\d+\\s*/\\s*\\d+|%|~|(?i:dps|estimate)");

    private KpqRunFixture run;
    private MockedStatic<AgentModeService> modes;
    private MockedStatic<AgentModeStateRuntime> modeState;
    private MockedStatic<AgentFarmAnchorStateRuntime> farmAnchors;
    private MockedStatic<AgentCombatVariationRuntime> variation;
    private MockedStatic<AgentCombatObjectiveTargetStateRuntime> objective;
    private MockedStatic<AgentGrindLootStateRuntime> loot;
    private MockedStatic<AgentGrindLootTargetService> lootTargets;
    private MockedStatic<AgentScriptItemActionService> items;
    private MockedStatic<AgentInventoryGatewayRuntime> inventory;
    private MockedStatic<AgentNavigationGraphService> graphs;
    private MockedStatic<AgentNavigationRegionService> regions;
    private MockedStatic<AgentAttackPlanScoringPolicy> scoring;
    private final InventoryGateway inventoryGateway = mock(InventoryGateway.class);
    private final Map<Character, Integer> passesHeld = new HashMap<>();
    /** What lies on stage 5's floor. */
    private final List<MapItem> floor = new CopyOnWriteArrayList<>();
    private long clockMs;
    private int nextObjectId = 40;
    private Character ben;
    private Character faro;
    private Character silentSin;

    @BeforeEach
    void setUp() {
        run = new KpqRunFixture();
        modes = mockStatic(AgentModeService.class);
        modeState = mockStatic(AgentModeStateRuntime.class);
        farmAnchors = mockStatic(AgentFarmAnchorStateRuntime.class);
        variation = mockStatic(AgentCombatVariationRuntime.class);
        objective = mockStatic(AgentCombatObjectiveTargetStateRuntime.class);
        loot = mockStatic(AgentGrindLootStateRuntime.class);
        lootTargets = mockStatic(AgentGrindLootTargetService.class);
        items = mockStatic(AgentScriptItemActionService.class);
        inventory = mockStatic(AgentInventoryGatewayRuntime.class);
        graphs = mockStatic(AgentNavigationGraphService.class);
        regions = mockStatic(AgentNavigationRegionService.class);
        scoring = mockStatic(AgentAttackPlanScoringPolicy.class);
        inventory.when(AgentInventoryGatewayRuntime::inventory).thenReturn(inventoryGateway);
        // A restore goes into the leader's bag.
        when(inventoryGateway.addItem(any(), anyInt(), anyShort())).thenAnswer(call -> {
            passesHeld.merge(call.getArgument(0), (int) call.<Short>getArgument(2), Integer::sum);
            return true;
        });
        // A drop that goes through leaves the bag and lands on the floor, owned by the dropper.
        items.when(() -> AgentScriptItemActionService.dropItem(
                        any(), eq(InventoryType.ETC), eq(AgentKpqDefinition.PASS_ITEM), anyShort()))
                .thenAnswer(call -> {
                    AgentRuntimeEntry dropper = call.getArgument(0);
                    short quantity = call.getArgument(3);
                    for (Character holder : List.copyOf(passesHeld.keySet())) {
                        if (entry(holder.getId()) != dropper) continue;
                        passesHeld.merge(holder, -quantity, Integer::sum);
                        floor.add(pass(holder, quantity, clockMs, holder.getPosition()));
                    }
                    return true;
                });
        // An expired drop leaves the floor, through Cosmic's own disappear.
        when(run.map.makeDisappearItemFromMap(any(MapItem.class))).thenAnswer(call -> floor.remove(call.<MapItem>getArgument(0)));
        AgentNavigationGraph graph = mock(AgentNavigationGraph.class);
        when(graph.canReach(anyInt(), anyInt())).thenAnswer(call -> {
            int from = call.getArgument(0);
            int to = call.getArgument(1);
            return from == to || to != LEDGE;
        });
        when(graph.findRegionId(any(), any())).thenAnswer(call -> region(call.getArgument(1)));
        graphs.when(() -> AgentNavigationGraphService.peekBestGraph(any(), any())).thenReturn(graph);
        regions.when(() -> AgentNavigationRegionService.resolveCurrentRegionId(any(), any(), any(), any()))
                .thenAnswer(call -> region(call.getArgument(3)));
        regions.when(() -> AgentNavigationRegionService.resolvePointTargetRegionId(any(), any(), any()))
                .thenAnswer(call -> region(call.getArgument(2)));
        NPC cloto = mock(NPC.class);
        when(cloto.getPosition()).thenReturn(CLOTO);
        when(run.map.getNPCById(AgentKpqDefinition.CLOTO_NPC)).thenReturn(cloto);
        when(run.map.getDroppedItems()).thenAnswer(call -> List.copyOf(floor));
        ben = onStageFive(run.human(1, "Ben"), BEN_BY_CLOTO);
        faro = onStageFive(run.companion(2, "Faro723"), FAR_BELOW);
        silentSin = onStageFive(run.companion(3, "SilentSin"), new Point(300, -451));
        holding(ben, 0);
    }

    @AfterEach
    void tearDown() {
        scoring.close();
        regions.close();
        graphs.close();
        inventory.close();
        items.close();
        lootTargets.close();
        loot.close();
        objective.close();
        variation.close();
        farmAnchors.close();
        modeState.close();
        modes.close();
        run.close();
    }

    @Test
    void passesLeftAtHisFeetAreMentionedOnceAndClearTheStageAfterThePatienceNotTheirExpiry() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, faro, silentSin);
        session.setMemberCoordinationChatEnabled(false);
        holding(faro, 4);
        holding(silentSin, 6);
        bothDropAtHisFeet(session);
        // He never picks any up.
        assertEquals(2, floor.size(), floor::toString);

        tickEverySecond(session, DROPPED_AT_MS + 1_000L, DROPPED_AT_MS + NUDGE_MS - 1_000L);
        assertNotNudged();
        tick(session, DROPPED_AT_MS + NUDGE_MS);
        // In the voice of the companion that dropped the oldest, with coordination chat off.
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(faro, NUDGE), times(1));

        // Past the patience the passes stop holding the recovery back; the grace then runs.
        long recoveryAtMs = DROPPED_AT_MS + PATIENCE_MS + GRACE_MS;
        tickEverySecond(session, DROPPED_AT_MS + NUDGE_MS + 1_000L, recoveryAtMs - 1_000L);
        verify(inventoryGateway, never()).addItem(any(), anyInt(), anyShort());

        try (KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class)) {
            tick(session, recoveryAtMs);
            List<String> restores = log.startingWith(RECOVERY);
            assertEquals(1, restores.size(), restores::toString);
            assertTrue(restores.getFirst().contains(" leaderPasses=0/10 restored=10 floorPasses=10 stalePasses=0 "
                    + "leftAtFeet=10 defect=true cause=left-at-feet recollect=false "), restores.getFirst());
            assertEquals(List.of(TAKEN_BACK + "session=" + session.sessionId() + " leader=Ben(1) floorDrops=2/2 "
                    + "floorPasses=10"), log.startingWith(TAKEN_BACK));
            tick(session, recoveryAtMs + 1_000L);
            // He picked none up: the made-up ten are not logged as his pickup, which a live check reads.
            assertEquals(List.of(), log.startingWith("KPQ pass pickup: "));
        }
        // 67 s after the drop, not 7 s after their 180 s expiry; the carry's pace span is kept out of the calibration.
        verify(inventoryGateway, times(1)).addItem(ben, AgentKpqDefinition.PASS_ITEM, (short) 10);
        assertTrue(session.pace().snapshot().stageFivePassesRestored());
        // None of the ten left lying stays on the floor beside the ten made up.
        assertEquals(List.of(), List.copyOf(floor));
        // They lie by him: nobody is sent back for them.
        assertFalse(said().contains(RECOLLECT), said()::toString);
        assertEquals(0, session.stage5RecollectorId());

        tick(session, recoveryAtMs + 2_000L);
        verify(inventoryGateway, times(1)).addItem(any(), anyInt(), anyShort());
        assertEquals(1, said().stream().filter(NUDGES::contains).count(), said()::toString);
        assertSpokenLikePlayers();
    }

    @Test
    void aPassYoungerThanThePatienceStillHoldsTheRecoveryBack() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, faro, silentSin);
        holding(faro, 4);
        holding(silentSin, 0);
        tick(session, 2_000L);
        tick(session, SET_OUT_MS);
        at(faro, AT_HIS_FEET);
        tick(session, DROPPED_AT_MS);
        assertEquals(1, floor.size(), floor::toString);
        // One more lands at his feet well after the first.
        long laterMs = DROPPED_AT_MS + 45_000L;
        tickEverySecond(session, DROPPED_AT_MS + 1_000L, laterMs - 1_000L);
        floor.add(pass(silentSin, 1, laterMs, AT_HIS_FEET));

        // The first is past the patience, the second is not: the recovery waits for the second.
        long recoveryAtMs = laterMs + PATIENCE_MS + GRACE_MS;
        tickEverySecond(session, laterMs, recoveryAtMs - 1_000L);
        verify(inventoryGateway, never()).addItem(any(), anyInt(), anyShort());

        try (KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class)) {
            tick(session, recoveryAtMs);
            assertTrue(log.startingWith(RECOVERY).getFirst().contains(" floorPasses=5 stalePasses=0 leftAtFeet=5 "
                    + "defect=true cause=left-at-feet "), log.startingWith(RECOVERY)::toString);
        }
        verify(inventoryGateway, times(1)).addItem(ben, AgentKpqDefinition.PASS_ITEM, (short) 10);
        assertEquals(List.of(), List.copyOf(floor));
    }

    @Test
    void outOfReachStalePassesAreStillFetchedAgainFirst() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, faro, silentSin);
        holding(faro, 4);
        holding(silentSin, 0);
        // One a kill left 2,300 px under Ben, nobody going for it.
        MapItem below = killPass(silentSin, 1, 1_000L, FAR_BELOW);
        floor.add(below);
        tick(session, 2_000L);
        tick(session, SET_OUT_MS);
        at(faro, AT_HIS_FEET);
        tick(session, DROPPED_AT_MS);

        // Stale from 30 s, but Faro's at his feet hold the gate until the patience; the grace after it then starts the
        // run's one re-collect, as before, from the companion nearest the stale one, instead of the recovery.
        long gateOpenMs = DROPPED_AT_MS + PATIENCE_MS;
        tickEverySecond(session, DROPPED_AT_MS + 1_000L, gateOpenMs + GRACE_MS - 1_000L);
        assertFalse(said().contains(RECOLLECT), said()::toString);
        tick(session, gateOpenMs + GRACE_MS);

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(silentSin, RECOLLECT), times(1));
        assertEquals(silentSin.getId(), session.stage5RecollectorId());
        verify(inventoryGateway, never()).addItem(any(), anyInt(), anyShort());
        tick(session, gateOpenMs + GRACE_MS + 1_000L);
        // The re-collect goes for the stale one only; the ones at his feet already lie by him.
        loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(entry(3), below), atLeast(1));
        assertTrue(STALE_MS < PATIENCE_MS);
    }

    @Test
    void aPassAKillOfTheDroppersLeftBelowIsNotTakenForOneItDroppedAtHisFeet() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, faro, silentSin);
        session.setMemberCoordinationChatEnabled(false);
        holding(faro, 4);
        holding(silentSin, 6);
        // One of Faro's kills dropped a pass 2,300 px below during the fight, and no sweep reached it: it is Faro's.
        floor.add(killPass(faro, 1, -30_000L, FAR_BELOW));
        bothDropAtHisFeet(session);

        // Not as Faro drops its four (the pass below is 35 s old by then), and never about a place he never stood.
        tickEverySecond(session, DROPPED_AT_MS + 1_000L, DROPPED_AT_MS + NUDGE_MS - 1_000L);
        assertNotNudged();
        tick(session, DROPPED_AT_MS + NUDGE_MS);

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(faro, NUDGE), times(1));
        assertEquals(1, said().stream().filter(NUDGES::contains).count(), said()::toString);
    }

    @Test
    void theNudgeComesFromTheCompanionThatDroppedTheOldestPasses() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, faro, silentSin);
        session.setMemberCoordinationChatEnabled(false);
        holding(faro, 4);
        holding(silentSin, 6);
        tick(session, 2_000L);
        tick(session, SET_OUT_MS);
        // SilentSin gets to his feet first; Faro, the run's first companion, a second later.
        at(silentSin, AT_HIS_FEET);
        tick(session, DROPPED_AT_MS);
        at(faro, AT_HIS_FEET);
        tick(session, DROPPED_AT_MS + 1_000L);
        assertEquals(2, floor.size(), floor::toString);

        tickEverySecond(session, DROPPED_AT_MS + 2_000L, DROPPED_AT_MS + NUDGE_MS - 1_000L);
        assertNotNudged();
        tick(session, DROPPED_AT_MS + NUDGE_MS);

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(silentSin, NUDGE), times(1));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(eq(faro), eq(NUDGE)), never());
    }

    @Test
    void aLeaderHoldingAllTenIsNotRemindedOfPassesStillAtHisFeet() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, faro, silentSin);
        holding(faro, 4);
        holding(silentSin, 6);
        bothDropAtHisFeet(session);
        // He has his ten from elsewhere, and both stacks still lie at his feet.
        passesHeld.put(ben, 10);

        tickEverySecond(session, DROPPED_AT_MS + 1_000L, DROPPED_AT_MS + NUDGE_MS + 5_000L);

        assertEquals(2, floor.size(), floor::toString);
        assertNotNudged();
    }

    @Test
    void aLeaderPickingUpPassesDuringTheGraceGetsItAfresh() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, faro, silentSin);
        holding(faro, 4);
        holding(silentSin, 6);
        bothDropAtHisFeet(session);
        long gateOpenMs = DROPPED_AT_MS + PATIENCE_MS;
        tickEverySecond(session, DROPPED_AT_MS + 1_000L, gateOpenMs + 3_000L);
        // He bends down for Faro's four, 3 s into the grace.
        MapItem faros = floor.stream().filter(drop -> drop.getOwnerId() == faro.getId()).findFirst().orElseThrow();
        floor.remove(faros);
        passesHeld.put(ben, 4);

        // The grace starts again from his pickup: SilentSin's six do not vanish from under him meanwhile.
        long pickedUpMs = gateOpenMs + 4_000L;
        tickEverySecond(session, pickedUpMs, pickedUpMs + GRACE_MS - 1_000L);
        verify(inventoryGateway, never()).addItem(any(), anyInt(), anyShort());
        assertEquals(1, floor.size(), floor::toString);

        // He leaves those: once the grace from his pickup is over, only the six he lacks are made up.
        tick(session, pickedUpMs + GRACE_MS);
        verify(inventoryGateway, times(1)).addItem(ben, AgentKpqDefinition.PASS_ITEM, (short) 6);
        assertEquals(List.of(), List.copyOf(floor));
    }

    @Test
    void aPassTheRecollectBringsAfterHeLeftOthersAtHisFeetWaitsOnlyTheNudge() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, faro, silentSin);
        holding(faro, 4);
        holding(silentSin, 0);
        // A kill's pass 2,300 px under Ben, nobody going for it; Faro drops its four at his feet.
        MapItem below = killPass(silentSin, 1, 1_000L, FAR_BELOW);
        floor.add(below);
        tick(session, 2_000L);
        tick(session, SET_OUT_MS);
        at(faro, AT_HIS_FEET);
        tick(session, DROPPED_AT_MS);
        // He leaves Faro's four past the patience; after the grace SilentSin goes back for the one below.
        long recollectAtMs = DROPPED_AT_MS + PATIENCE_MS + GRACE_MS;
        tickEverySecond(session, DROPPED_AT_MS + 1_000L, recollectAtMs);
        assertEquals(silentSin.getId(), session.stage5RecollectorId());
        // It picks it up 15 s later and is at his feet with it 20 s after that.
        long pickedUpMs = recollectAtMs + 15_000L;
        tickEverySecond(session, recollectAtMs + 1_000L, pickedUpMs - 1_000L);
        floor.remove(below);
        passesHeld.put(silentSin, 1);
        long backMs = pickedUpMs + 20_000L;
        tickEverySecond(session, pickedUpMs, backMs - 1_000L);
        at(silentSin, AT_HIS_FEET);
        tick(session, backMs);
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(silentSin, RECOLLECTED), times(1));

        // He has already shown he leaves passes at his feet: the one it brings counts as left after the nudge's
        // wait, not the whole patience again, which would put the clear back near the passes' expiry.
        long recoveryAtMs = backMs + NUDGE_MS + GRACE_MS;
        tickEverySecond(session, backMs + 1_000L, recoveryAtMs - 1_000L);
        verify(inventoryGateway, never()).addItem(any(), anyInt(), anyShort());
        try (KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class)) {
            tick(session, recoveryAtMs);
            assertTrue(log.startingWith(RECOVERY).getFirst().contains(" floorPasses=5 stalePasses=0 leftAtFeet=5 "
                    + "defect=true cause=left-at-feet recollect=true "), log.startingWith(RECOVERY)::toString);
        }
        verify(inventoryGateway, times(1)).addItem(ben, AgentKpqDefinition.PASS_ITEM, (short) 10);
        assertEquals(List.of(), List.copyOf(floor));
        assertEquals(1, said().stream().filter(RECOLLECT::equals).count(), said()::toString);
    }

    @Test
    void aPassTheRecollectBringsWaitsTheWholePatienceWhenHeLeftNoneAtHisFeetBefore() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, faro, silentSin);
        holding(faro, 0);
        holding(silentSin, 0);
        // Faro waits up by Ben; only a kill's pass lies 2,300 px under him, nobody going for it, none at his feet.
        at(faro, AT_HIS_FEET);
        MapItem below = killPass(silentSin, 1, 1_000L, FAR_BELOW);
        floor.add(below);
        // Stale after its patience; after the grace SilentSin goes back for it, as before.
        long recollectAtMs = 1_000L + STALE_MS + GRACE_MS;
        tickEverySecond(session, 2_000L, recollectAtMs);
        assertEquals(silentSin.getId(), session.stage5RecollectorId());
        long pickedUpMs = recollectAtMs + 5_000L;
        tickEverySecond(session, recollectAtMs + 1_000L, pickedUpMs - 1_000L);
        floor.remove(below);
        passesHeld.put(silentSin, 1);
        long backMs = pickedUpMs + 10_000L;
        tickEverySecond(session, pickedUpMs, backMs - 1_000L);
        at(silentSin, AT_HIS_FEET);
        tick(session, backMs);
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(silentSin, RECOLLECTED), times(1));

        // He has left nothing at his feet yet: the pass it brings gets the whole in-reach patience, and a reminder.
        long recoveryAtMs = backMs + PATIENCE_MS + GRACE_MS;
        tickEverySecond(session, backMs + 1_000L, recoveryAtMs - 1_000L);
        verify(inventoryGateway, never()).addItem(any(), anyInt(), anyShort());
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(silentSin, NUDGE_ONE), times(1));
        tick(session, recoveryAtMs);
        verify(inventoryGateway, times(1)).addItem(ben, AgentKpqDefinition.PASS_ITEM, (short) 10);
        assertEquals(List.of(), List.copyOf(floor));
    }

    @Test
    void passesLeftByClotoLongBeforeHeComesAreNotMadeUpAsHeWalksUpToThem() {
        // Ben is down below on his way up; ten passes left by Cloto lie there from 1 s.
        at(ben, new Point(150, FAR_BELOW.y));
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, faro, silentSin);
        holding(faro, 0);
        holding(silentSin, 0);
        MapItem byCloto = pass(faro, 10, 1_000L, new Point(CLOTO.x - 20, CLOTO.y));
        floor.add(byCloto);
        long arrivesMs = 100_000L;
        tickEverySecond(session, 2_000L, arrivesMs - 1_000L);
        verify(inventoryGateway, never()).addItem(any(), anyInt(), anyShort());

        // He comes up beside them 99 s after they were left: the patience runs from now, not from their drop.
        at(ben, BEN_BY_CLOTO);
        long recoveryAtMs = arrivesMs + PATIENCE_MS + GRACE_MS;
        tickEverySecond(session, arrivesMs, recoveryAtMs - 1_000L);
        verify(inventoryGateway, never()).addItem(any(), anyInt(), anyShort());
        assertEquals(List.of(byCloto), List.copyOf(floor));

        try (KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class)) {
            tick(session, recoveryAtMs);
            assertTrue(log.startingWith(RECOVERY).getFirst().contains(" floorPasses=10 stalePasses=0 leftAtFeet=10 "
                    + "defect=true cause=left-at-feet "), log.startingWith(RECOVERY)::toString);
        }
        verify(inventoryGateway, times(1)).addItem(ben, AgentKpqDefinition.PASS_ITEM, (short) 10);
        assertEquals(List.of(), List.copyOf(floor));
        // They were never dropped on his spot: nobody reminds him of them.
        assertNotNudged();
    }

    @Test
    void oneLeftPassIsMentionedInTheSingular() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, faro, silentSin);
        holding(faro, 1);
        holding(silentSin, 0);
        tick(session, 2_000L);
        tick(session, SET_OUT_MS);
        at(faro, AT_HIS_FEET);
        tick(session, DROPPED_AT_MS);

        tick(session, DROPPED_AT_MS + NUDGE_MS);

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(faro, NUDGE_ONE), times(1));
    }

    @Test
    void aLeaderWhoWalkedOffFromThePassesIsToldTheyAreBackThere() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, faro, silentSin);
        holding(faro, 4);
        holding(silentSin, 6);
        bothDropAtHisFeet(session);
        // He walks off along Cloto's island, out of their reach.
        at(ben, new Point(100, BEN_BY_CLOTO.y));

        tick(session, DROPPED_AT_MS + NUDGE_MS);

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(faro, NUDGE_BACK_THERE), times(1));
        assertNotNudgedWith(NUDGE);
    }

    @Test
    void passesLeftByClotoAreNotMentionedAgain() {
        // Ben stands on Cloto's island, a little way from her; Faro's route never gets it near him.
        at(ben, new Point(180, CLOTO.y));
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, faro, silentSin);
        holding(faro, 10);
        holding(silentSin, 0);
        Point byCloto = AgentKpqCoordinator.npcApproachPoint(session, session.member(faro.getId()), faro, CLOTO, 501);
        tick(session, 2_000L);
        tick(session, SET_OUT_MS);
        tick(session, SET_OUT_MS + HANDOVER_MS);
        at(faro, byCloto);
        long droppedMs = SET_OUT_MS + HANDOVER_MS + 5_000L;
        tick(session, droppedMs);
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(faro, "Ben, couldn't get to you, left the passes by cloto."));
        assertEquals(1, floor.size(), floor::toString);

        // It already told him where they are.
        tickEverySecond(session, droppedMs + 1_000L, droppedMs + NUDGE_MS + 5_000L);
        assertNotNudged();
    }

    @Test
    void aStockHumanLedRunStillWaitsForAPassAtHisFeetHoweverOldAndKeepsIt() {
        AgentKpqSession session = run.session(false, AgentKpqSession.Phase.STAGE_5, ben, faro, silentSin);
        MapItem atHisFeet = pass(faro, 4, DROPPED_AT_MS, AT_HIS_FEET);
        floor.add(atHisFeet);

        tickEverySecond(session, DROPPED_AT_MS + 1_000L, DROPPED_AT_MS + PATIENCE_MS + GRACE_MS * 3);

        verify(inventoryGateway, never()).addItem(any(), anyInt(), anyShort());
        verify(run.map, never()).makeDisappearItemFromMap(any(MapItem.class));
        assertEquals(List.of(atHisFeet), List.copyOf(floor));
        assertNotNudged();
    }

    @Test
    void anAllAgentRunStillWaitsForAPassAtItsLeadersFeetHoweverOldAndKeepsIt() {
        Character kai = onStageFive(run.companion(4, "Kai"), BEN_BY_CLOTO);
        run.leaveParty(ben);
        run.leadParty(kai);
        holding(kai, 0);
        AgentKpqSession session = run.session(false, AgentKpqSession.Phase.STAGE_5, kai, faro, silentSin);
        MapItem atHisFeet = pass(faro, 4, DROPPED_AT_MS, AT_HIS_FEET);
        floor.add(atHisFeet);

        tickEverySecond(session, DROPPED_AT_MS + 1_000L, DROPPED_AT_MS + PATIENCE_MS + GRACE_MS * 3);

        verify(inventoryGateway, never()).addItem(any(), anyInt(), anyShort());
        verify(run.map, never()).makeDisappearItemFromMap(any(MapItem.class));
        assertEquals(List.of(atHisFeet), List.copyOf(floor));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq("Kai, the passes are right by your feet.")),
                never());
    }

    @Test
    void passesClotosStairsWouldCatchAreDroppedFromWhereTheyLandOnTheLeadersFloor() {
        // Stage 5's own geometry: Cloto stands under the stairs up to the bonus portal, whose four steps run 11 to 45
        // px over her floor from x 264 to 420, and the map lands a drop on the first foothold under a point 85 px over
        // the dropper. Live (run 3, kpq-a6f696f0) passes dropped beside a leader by her were likely caught so.
        MapleMap stageFive = AgentNavigationMapLoader.loadMapGeometry(AgentKpqDefinition.STAGE_5_MAP);
        stageFive.generateMapDropRangeCache();
        doAnswer(call -> stageFive.getPointBelow(new Point(call.<Point>getArgument(0))))
                .when(run.map).getPointBelow(any());
        doAnswer(call -> stageFive.calcDropPos(call.getArgument(0), call.getArgument(1)))
                .when(run.map).calcDropPos(any(), any());
        items.when(() -> AgentScriptItemActionService.dropItem(
                        eq(entry(2)), eq(InventoryType.ETC), eq(AgentKpqDefinition.PASS_ITEM), anyShort()))
                .thenAnswer(call -> {
                    passesHeld.put(faro, 0);
                    Point from = new Point(faro.getPosition());
                    floor.add(pass(faro, call.<Short>getArgument(3), clockMs, run.map.calcDropPos(from, from)));
                    return true;
                });
        Point byCloto = new Point(300, -2715);
        Point besideHim = new Point(306, -2715);
        at(ben, byCloto);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, faro, silentSin);
        holding(faro, 4);
        holding(silentSin, 0);
        tick(session, 2_000L);
        tick(session, SET_OUT_MS);

        // Beside him they would land on the top step, 45 px over his head: Faro walks along his floor to the
        // nearest spot clear of the stairs instead of dropping them there.
        at(faro, besideHim);
        tick(session, DROPPED_AT_MS);
        assertEquals(new Point(306, -2760), stageFive.calcDropPos(new Point(besideHim), new Point(besideHim)));
        items.verify(() -> AgentScriptItemActionService.dropItem(
                eq(entry(2)), eq(InventoryType.ETC), eq(AgentKpqDefinition.PASS_ITEM), anyShort()), never());
        Point spot = new Point(250, -2715);
        modes.verify(() -> AgentModeService.startMoveTo(entry(2), spot, true));

        at(faro, spot);
        try (KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class)) {
            tick(session, DROPPED_AT_MS + 1_000L);

            items.verify(() -> AgentScriptItemActionService.dropItem(
                    entry(2), InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 4), times(1));
            assertEquals(List.of("KPQ pass drop: session=" + session.sessionId() + " stage=5 agent=Faro723 "
                            + "reason=beside passes=4 dx=-50 dy=0 sameFoothold=true leaderFooting=GROUND spot=moved "
                            + "predicted=(250,-2715) landed=[(250,-2715):dx=-50:dy=0:passes=4:oid=40:ffa=false]"),
                    log.startingWith("KPQ pass drop: "));
        }
        // On his floor, in his reach.
        assertEquals(List.of(spot), floor.stream().map(MapItem::getPosition).toList());
    }

    @Test
    void eachDropLogsWhereItsPassesLanded() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, faro, silentSin);
        holding(faro, 4);
        holding(silentSin, 6);
        // Faro's land on the foothold 60 px over his head, and a pass its last kill left lands at the same moment,
        // owned by it too; SilentSin's drop leaves nothing on the floor.
        items.when(() -> AgentScriptItemActionService.dropItem(
                        eq(entry(2)), eq(InventoryType.ETC), eq(AgentKpqDefinition.PASS_ITEM), anyShort()))
                .thenAnswer(call -> {
                    passesHeld.put(faro, 0);
                    floor.add(killPass(faro, 1, clockMs, FAR_BELOW));
                    floor.add(pass(faro, 4, clockMs, new Point(AT_HIS_FEET.x, AT_HIS_FEET.y - 60)));
                    return true;
                });
        items.when(() -> AgentScriptItemActionService.dropItem(
                        eq(entry(3)), eq(InventoryType.ETC), eq(AgentKpqDefinition.PASS_ITEM), anyShort()))
                .thenAnswer(call -> {
                    passesHeld.put(silentSin, 0);
                    return true;
                });
        // An older drop of Faro's own, from before, is not this drop.
        floor.add(pass(faro, 1, -60_000L, FAR_BELOW));

        try (KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class)) {
            bothDropAtHisFeet(session);

            assertEquals(List.of(
                            "KPQ pass drop: session=" + session.sessionId() + " stage=5 agent=Faro723 reason=beside "
                                    + "passes=4 dx=-6 dy=0 sameFoothold=true leaderFooting=GROUND spot=feet "
                                    + "predicted=? landed=[(254,-2777):dx=-6:dy=-60:passes=4:oid=42:ffa=false]",
                            "KPQ pass drop: session=" + session.sessionId() + " stage=5 agent=SilentSin "
                                    + "reason=beside passes=6 dx=-6 dy=0 sameFoothold=true leaderFooting=GROUND "
                                    + "spot=feet predicted=? landed=none"),
                    log.startingWith("KPQ pass drop: "));
        }
    }

    @Test
    void theNudgeLinesSoundLikePlayers() {
        for (String line : List.of(AgentKpqPassHandOver.stageFiveNudgeLine("Ben", 1, true),
                AgentKpqPassHandOver.stageFiveNudgeLine("Ben", 10, true),
                AgentKpqPassHandOver.stageFiveNudgeLine("Ben", 1, false),
                AgentKpqPassHandOver.stageFiveNudgeLine("Ben", 10, false))) {
            String spoken = line.substring("Ben".length());
            assertEquals(spoken.toLowerCase(java.util.Locale.ROOT), spoken, line);
            assertFalse(ENGINE.matcher(line).find(), line);
            assertTrue(NUDGES.contains(line), line);
        }
        assertEquals(NUDGE, AgentKpqPassHandOver.stageFiveNudgeLine("Ben", 10, true));
    }

    @Test
    void thePatienceRuleCountsOnlyAnOldUnfetchedPassInHisReach() {
        assertTrue(AgentKpqPassHandOver.leftAtFeet(PATIENCE_MS, true, false, PATIENCE_MS));
        assertFalse(AgentKpqPassHandOver.leftAtFeet(PATIENCE_MS - 1L, true, false, PATIENCE_MS));
        assertFalse(AgentKpqPassHandOver.leftAtFeet(PATIENCE_MS, false, false, PATIENCE_MS));
        assertFalse(AgentKpqPassHandOver.leftAtFeet(PATIENCE_MS, true, true, PATIENCE_MS));
    }

    /** They set out at 4.1 s, stand by Ben (dx=-6, as live) and drop there at 5 s. */
    private void bothDropAtHisFeet(AgentKpqSession session) {
        tick(session, 2_000L);
        tick(session, SET_OUT_MS);
        at(faro, AT_HIS_FEET);
        at(silentSin, AT_HIS_FEET);
        tick(session, DROPPED_AT_MS);
        items.verify(() -> AgentScriptItemActionService.dropItem(
                entry(2), InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 4), times(1));
        items.verify(() -> AgentScriptItemActionService.dropItem(
                entry(3), InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 6), times(1));
    }

    private void tick(AgentKpqSession session, long nowMs) {
        clockMs = nowMs;
        AgentKpqCoordinator.tick(session, nowMs);
    }

    private void tickEverySecond(AgentKpqSession session, long fromMs, long toMs) {
        for (long nowMs = fromMs; nowMs <= toMs; nowMs += 1_000L) {
            tick(session, nowMs);
        }
    }

    private void assertNotNudged() {
        for (String line : NUDGES) assertNotNudgedWith(line);
    }

    private void assertNotNudgedWith(String line) {
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(line)), never());
    }

    private void assertSpokenLikePlayers() {
        for (String line : said()) {
            assertFalse(line.contains("->"), () -> "status arrow: " + line);
            assertFalse(Pattern.compile("\\d+\\s*/\\s*\\d+").matcher(line).find(), () -> "bare counter: " + line);
        }
    }

    private List<String> said() {
        ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), lines.capture()), atLeast(0));
        return lines.getAllValues();
    }

    private void holding(Character member, int passes) {
        passesHeld.put(member, passes);
        when(member.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false))
                .thenAnswer(call -> passesHeld.get(member));
    }

    /** A stack of passes {@code owner} dropped at {@code droppedAtMs}, as the map keeps it. */
    private MapItem pass(Character owner, int quantity, long droppedAtMs, Point position) {
        return pass(owner, quantity, droppedAtMs, position, true);
    }

    /** A pass a kill of {@code owner}'s left lying at {@code droppedAtMs}: the killer owns it, but did not drop it. */
    private MapItem killPass(Character owner, int quantity, long droppedAtMs, Point position) {
        return pass(owner, quantity, droppedAtMs, position, false);
    }

    private MapItem pass(Character owner, int quantity, long droppedAtMs, Point position, boolean playerDrop) {
        // Read before stubbing: the owner is a mock too.
        int ownerId = owner.getId();
        MapItem drop = mock(MapItem.class);
        Item stack = mock(Item.class);
        when(stack.getQuantity()).thenReturn((short) quantity);
        when(drop.getItem()).thenReturn(stack);
        when(drop.getItemId()).thenReturn(AgentKpqDefinition.PASS_ITEM);
        when(drop.getObjectId()).thenReturn(nextObjectId++);
        when(drop.getOwnerId()).thenReturn(ownerId);
        when(drop.getDropTime()).thenReturn(droppedAtMs);
        when(drop.getPosition()).thenReturn(new Point(position));
        when(drop.isPlayerDrop()).thenReturn(playerDrop);
        return drop;
    }

    /** Stage 5's shape: the arrival ledge left of the gap, Cloto's island right of it, and everything below. */
    private static int region(Point point) {
        if (point == null) return -1;
        if (point.y > -2700) return BELOW;
        return point.x < 90 ? LEDGE : CLOTOS_ISLAND;
    }

    private static Character onStageFive(Character member, Point position) {
        when(member.getMapId()).thenReturn(AgentKpqDefinition.STAGE_5_MAP);
        return at(member, position);
    }

    private static Character at(Character member, Point position) {
        when(member.getPosition()).thenReturn(new Point(position));
        return member;
    }

    private static AgentRuntimeEntry entry(int companionId) {
        return AgentRuntimeRegistry.findByAgentCharacterId(companionId);
    }

    private static long tuning(String key) {
        return config.AgentTuning.longValue("server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator." + key);
    }
}
