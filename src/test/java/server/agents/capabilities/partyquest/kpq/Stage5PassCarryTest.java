package server.agents.capabilities.partyquest.kpq;

import client.Character;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentAttackPlanScoringPolicy;
import server.agents.capabilities.combat.AgentCombatObjectiveTargetStateRuntime;
import server.agents.capabilities.combat.AgentCombatVariationRuntime;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.looting.AgentGrindLootStateRuntime;
import server.agents.capabilities.looting.AgentGrindLootTargetService;
import server.agents.capabilities.looting.AgentLootEligibility;
import server.agents.capabilities.movement.AgentFarmAnchorStateRuntime;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.capabilities.navigation.AgentNavigationRegionService;
import server.agents.capabilities.supplies.AgentSupplyInventorySnapshot;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.plans.AgentScriptItemActionService;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.life.Monster;
import server.life.NPC;
import server.maps.MapItem;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A companion run's companions carry the stage-5 passes to the human leader (pace plan C2). Live (runs 6 and D) only
 * the leader could pick them up: they lay 2,300 px below Cloto for 106-134 s, or expired there. Live again (2026-10-01,
 * pace1b) the leader stood on the arrival ledge, which nothing below can reach: the carriers stood still for the whole
 * hand-over, dropped the passes 2,300 px under him, and those passes held the recovery back until the timer ran out.
 *
 * <p>The navigation graph here is the stage's shape: the arrival ledge left of the gap, Cloto's island right of it,
 * and everything below. Nothing reaches the ledge but the ledge itself.
 */
class Stage5PassCarryTest {
    private static final int JR_NECKI = 9_300_000;
    private static final int CURSE_EYE = 9_300_002;
    private static final int KING_SLIME = 9_300_003;
    private static final int LEDGE = 5;
    private static final int CLOTOS_ISLAND = 6;
    private static final int BELOW = 19;
    private static final Point BEN_ON_ARRIVAL_LEDGE = new Point(-210, -2721);
    private static final Point BEN_BY_NELLA = new Point(-100, -2717);
    private static final Point BEN_BY_CLOTO = new Point(260, -2717);
    private static final Point CLOTO = new Point(305, -2717);
    private static final Point KING_SLIMES_FLOOR = new Point(160, -451);
    private static final String ENTER = "let's clear these out and get the 10 passes.";
    private static final String ROUTE = "Ben, cloto's across the gap on the right. hop down and take the two ladders up,"
            + " we'll bring the passes there.";
    private static final String ROUTE_AGAIN =
            "Ben, you still up there? drop off the ledge and take the two ladders on the right up to cloto.";
    private static final String CAREFUL = "careful, curse eyes down there.";
    private static final String AT_FEET = "Ben, the passes are at your feet. grab them and talk to cloto.";
    private static final String GRAB_SHOES = "i'll grab the squishy shoes.";
    private static final String BY_CLOTO = "Ben, couldn't get to you, left the passes by cloto.";
    private static final String RIGHT_HERE = "Ben, can't get to you, i dropped the passes right here by me.";
    private static final String LEAVING = "almost out of pots, leaving my passes here, grab them.";
    private static final String RECOLLECT = "i'll go grab the passes still lying around.";
    private static final String RECOLLECTED = "Ben, got the rest of the passes, they're at your feet.";
    private static final long HANDOVER_MS = tuning("STAGE5_PASS_HANDOVER_MS");
    private static final long PATIENCE_MS = tuning("STAGE5_FLOOR_PASS_PATIENCE_MS");
    private static final long SWEEP_LIMIT_MS = tuning("STAGE5_SWEEP_LIMIT_MS");
    private static final long REPEAT_MS = tuning("STAGE5_ROUTE_REPEAT_MS");
    private static final long GRACE_MS = tuning("STAGE5_MISSING_PASS_GRACE_MS");
    // King Slime's revive grace runs out at 4 s in these runs; nothing is left to fetch, so they set out then.
    private static final long SET_OUT_MS = 4_100L;

    private KpqRunFixture run;
    // The coordinator's own gateway is the real one; these are what its grind, move, stop, loot and drop calls reach.
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
    // Which mob each one hurts most is AgentKpqStageFiveTargetPolicyTest's; here every estimate is the same.
    private MockedStatic<AgentAttackPlanScoringPolicy> scoring;
    private final InventoryGateway inventoryGateway = mock(InventoryGateway.class);
    private final Map<Character, Integer> passesHeld = new HashMap<>();
    private final Set<Integer> unreachable = new HashSet<>(Set.of(LEDGE));
    private final List<MapItem> floor = new ArrayList<>();
    private Character ben;
    private Character mira;
    private Character pip;

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
        when(inventoryGateway.addItem(any(), anyInt(), anyShort())).thenReturn(true);
        // Passes leave the bag when their drop goes through.
        items.when(() -> AgentScriptItemActionService.dropItem(
                        any(), eq(InventoryType.ETC), eq(AgentKpqDefinition.PASS_ITEM), anyShort()))
                .thenAnswer(call -> {
                    AgentRuntimeEntry dropper = call.getArgument(0);
                    short quantity = call.getArgument(3);
                    passesHeld.replaceAll((holder, held) ->
                            entry(holder.getId()) == dropper ? held - quantity : held);
                    return true;
                });
        AgentNavigationGraph graph = mock(AgentNavigationGraph.class);
        when(graph.canReach(anyInt(), anyInt())).thenAnswer(call -> {
            int from = call.getArgument(0);
            int to = call.getArgument(1);
            return from == to || !unreachable.contains(to);
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
        mira = onStageFive(run.companion(2, "Mira"), new Point(0, -451));
        pip = onStageFive(run.companion(3, "Pip"), new Point(300, -451));
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
    void whileMonstersLiveTheCompanionsKeepThePassesTheyPickUp() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        fighting(JR_NECKI);
        holding(mira, 3);

        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, 3_000L);

        assertEquals(AgentKpqMemberState.Role.STAGE5_PASS_CARRIER, session.member(2).role());
        assertTrue(AgentKpqSessionRegistry.canLootPass(2));
        items.verify(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort()), never());
        modes.verify(() -> AgentModeService.startMoveTo(any(), eq(BEN_BY_CLOTO), anyBoolean()), never());
    }

    @Test
    void aStockHumanLedRunStillLeavesThePassesToItsLeader() {
        AgentKpqSession session = run.session(false, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        fighting(JR_NECKI);

        AgentKpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentKpqMemberState.Role.COMBAT_HELPER, session.member(2).role());
        assertFalse(AgentKpqSessionRegistry.canLootPass(2));
        assertTrue(said().contains("Ben, please collect the 10 passes while we clear the monsters."), said()::toString);
        assertFalse(said().contains(ROUTE), said()::toString);
    }

    @Test
    void aStockRunStillWaitsForEveryFloorPassHoweverOld() {
        AgentKpqSession session = run.session(false, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        floor.add(pass(31, 1_000L, new Point(0, -451)));

        for (long nowMs = 2_000L; nowMs <= 2_000L + PATIENCE_MS + SWEEP_LIMIT_MS + GRACE_MS * 2; nowMs += 1_000L) {
            AgentKpqCoordinator.tick(session, nowMs);
        }

        verify(inventoryGateway, never()).addItem(any(), anyInt(), anyShort());
        assertFalse(said().contains(RECOLLECT), said()::toString);
        assertEquals(0, session.stage5RecollectorId());
    }

    @Test
    void theLeaderIsToldTheWayToClotoAndNeverNudgedToCollect() {
        at(ben, BEN_ON_ARRIVAL_LEDGE.x, BEN_ON_ARRIVAL_LEDGE.y);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        fighting(JR_NECKI);

        // Long past the delayed nudge's 30 s: still on the ledge after the repeat delay, he hears the way once more.
        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, 2_000L + REPEAT_MS - 1_000L);
        assertEquals(List.of(ENTER, ROUTE), said());
        AgentKpqCoordinator.tick(session, 2_000L + REPEAT_MS);
        AgentKpqCoordinator.tick(session, 2_000L + REPEAT_MS * 3);

        assertEquals(List.of(ENTER, ROUTE, ROUTE_AGAIN), said());
    }

    @Test
    void withCoordinationChatOffTheLedgeLeaderStillHearsTheWayAndItsOneRepeat() {
        at(ben, BEN_ON_ARRIVAL_LEDGE.x, BEN_ON_ARRIVAL_LEDGE.y);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        // Guidance off: no narrator and no status chatter. His passes wait by Cloto, so only the way moves him.
        session.setMemberCoordinationChatEnabled(false);
        fighting(CURSE_EYE, JR_NECKI);

        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, 2_000L + REPEAT_MS);
        AgentKpqCoordinator.tick(session, 2_000L + REPEAT_MS * 3);

        assertEquals(List.of(ROUTE, CAREFUL, ROUTE_AGAIN, CAREFUL), said());
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(mira, ROUTE));
    }

    @Test
    void aLeaderWhoLeftTheLedgeIsNotToldTheWayAgain() {
        at(ben, BEN_BY_NELLA.x, BEN_BY_NELLA.y);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        fighting(JR_NECKI);

        AgentKpqCoordinator.tick(session, 2_000L);
        // He dropped down off the ledge, on his way to the ladders.
        at(ben, 200, -2175);
        AgentKpqCoordinator.tick(session, 2_000L + REPEAT_MS);
        AgentKpqCoordinator.tick(session, 2_000L + REPEAT_MS * 2);

        assertEquals(List.of(ENTER, ROUTE), said());
    }

    @Test
    void curseEyesStillBelowAreMentionedWithTheWay() {
        at(ben, BEN_ON_ARRIVAL_LEDGE.x, BEN_ON_ARRIVAL_LEDGE.y);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        fighting(CURSE_EYE, JR_NECKI);

        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, 3_000L);

        assertEquals(List.of(ENTER, ROUTE, CAREFUL), said());
    }

    @Test
    void aLeaderOnTheArrivalLedgeIsMetAtCloto() {
        at(ben, BEN_ON_ARRIVAL_LEDGE.x, BEN_ON_ARRIVAL_LEDGE.y);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        holding(mira, 6);
        holding(pip, 4);
        Point miraByCloto = clotoApproach(session, mira);

        // Well past the hand-over bound, and past the old 90 s one too.
        for (long nowMs = 2_000L; nowMs <= SET_OUT_MS + HANDOVER_MS * 3; nowMs += 5_000L) {
            AgentKpqCoordinator.tick(session, nowMs);
        }

        modes.verify(() -> AgentModeService.startMoveTo(entry(2), miraByCloto, true), atLeastOnce());
        modes.verify(() -> AgentModeService.startMoveTo(any(), eq(BEN_ON_ARRIVAL_LEDGE), anyBoolean()), never());
        items.verify(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort()), never());
        assertFalse(said().contains(RIGHT_HERE), said()::toString);
        assertFalse(said().contains(BY_CLOTO), said()::toString);
        // Held passes cannot expire, and the recovery waits for them.
        verify(inventoryGateway, never()).addItem(any(), anyInt(), anyShort());
    }

    @Test
    void theLedgeLeaderGetsAllTenPassesAtHisFeetOnceHeIsDownFromIt() {
        at(ben, BEN_ON_ARRIVAL_LEDGE.x, BEN_ON_ARRIVAL_LEDGE.y);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        holding(mira, 6);
        holding(pip, 4);
        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, SET_OUT_MS);
        long waitedMs = SET_OUT_MS + HANDOVER_MS * 2;
        for (long nowMs = SET_OUT_MS + 5_000L; nowMs <= waitedMs; nowMs += 5_000L) {
            AgentKpqCoordinator.tick(session, nowMs);
        }
        // They wait by Cloto.
        at(mira, CLOTO.x + 30, CLOTO.y);
        at(pip, CLOTO.x + 40, CLOTO.y);

        // He took the ladders up; the clock of their delivery starts only now, so none of it ran out by Cloto.
        at(ben, BEN_BY_CLOTO.x, BEN_BY_CLOTO.y);
        AgentKpqCoordinator.tick(session, waitedMs + 1_000L);
        modes.verify(() -> AgentModeService.startMoveTo(entry(2), BEN_BY_CLOTO, true));
        modes.verify(() -> AgentModeService.startMoveTo(entry(3), BEN_BY_CLOTO, true));
        at(mira, BEN_BY_CLOTO.x + 6, BEN_BY_CLOTO.y);
        at(pip, BEN_BY_CLOTO.x - 6, BEN_BY_CLOTO.y);
        AgentKpqCoordinator.tick(session, waitedMs + 2_000L);

        items.verify(() -> AgentScriptItemActionService.dropItem(
                entry(2), InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 6), times(1));
        items.verify(() -> AgentScriptItemActionService.dropItem(
                entry(3), InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 4), times(1));
        assertEquals(1, said().stream().filter(AT_FEET::equals).count(), said()::toString);
        assertFalse(said().contains(BY_CLOTO), said()::toString);
    }

    @Test
    void onceNothingIsLeftToFightTheyBringThePassesUpAndTheLastOneSaysSo() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        holding(mira, 6);
        holding(pip, 4);

        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, SET_OUT_MS);
        AgentKpqCoordinator.tick(session, 5_000L);

        modes.verify(() -> AgentModeService.startMoveTo(entry(2), BEN_BY_CLOTO, true));
        modes.verify(() -> AgentModeService.startMoveTo(entry(3), BEN_BY_CLOTO, true));
        // From here none of them picks a pass back up, not even one another dropped at Ben's feet.
        assertFalse(AgentKpqSessionRegistry.canLootPass(2));
        assertFalse(AgentKpqSessionRegistry.canLootPass(3));

        at(mira, BEN_BY_CLOTO.x + 8, BEN_BY_CLOTO.y);
        at(pip, BEN_BY_CLOTO.x - 8, BEN_BY_CLOTO.y);
        AgentKpqCoordinator.tick(session, 6_000L);
        AgentKpqCoordinator.tick(session, 7_000L);

        items.verify(() -> AgentScriptItemActionService.dropItem(
                entry(2), InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 6), times(1));
        items.verify(() -> AgentScriptItemActionService.dropItem(
                entry(3), InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 4), times(1));
        // Once all ten are down, from the last one there.
        assertEquals(1, said().stream().filter(AT_FEET::equals).count(), said()::toString);
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(pip, AT_FEET));
    }

    @Test
    void passesAreDroppedOnTheLeadersSpotNotBesideIt() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        holding(mira, 10);
        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, SET_OUT_MS);

        // 30 px off is where the old rule dropped; a leader standing still could not pick them up from there.
        at(mira, BEN_BY_CLOTO.x + 30, BEN_BY_CLOTO.y);
        AgentKpqCoordinator.tick(session, 5_000L);
        items.verify(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort()), never());
        modes.verify(() -> AgentModeService.startMoveTo(entry(2), BEN_BY_CLOTO, true), atLeastOnce());

        at(mira, BEN_BY_CLOTO.x + 8, BEN_BY_CLOTO.y);
        AgentKpqCoordinator.tick(session, 6_000L);
        items.verify(() -> AgentScriptItemActionService.dropItem(
                entry(2), InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 10), times(1));
    }

    @Test
    void noPassIsDroppedWhileTheLeaderIsOnALadderOrInTheAir() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        holding(mira, 10);
        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, SET_OUT_MS);
        at(mira, BEN_BY_CLOTO.x, BEN_BY_CLOTO.y);

        when(ben.getStance()).thenReturn(15);
        AgentKpqCoordinator.tick(session, 5_000L);
        when(ben.getStance()).thenReturn(6);
        AgentKpqCoordinator.tick(session, 6_000L);
        items.verify(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort()), never());

        when(ben.getStance()).thenReturn(4);
        AgentKpqCoordinator.tick(session, 7_000L);
        items.verify(() -> AgentScriptItemActionService.dropItem(
                entry(2), InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 10), times(1));
    }

    @Test
    void aCarrierStillFetchingAPassHoldsTheOthersBack() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        holding(mira, 6);
        holding(pip, 4);
        fetching(pip, true);

        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, SET_OUT_MS);
        AgentKpqCoordinator.tick(session, 5_000L);

        modes.verify(() -> AgentModeService.startMoveTo(any(), eq(BEN_BY_CLOTO), anyBoolean()), never());
        assertTrue(AgentKpqSessionRegistry.canLootPass(2));

        fetching(pip, false);
        AgentKpqCoordinator.tick(session, 6_000L);
        AgentKpqCoordinator.tick(session, 7_000L);

        modes.verify(() -> AgentModeService.startMoveTo(entry(2), BEN_BY_CLOTO, true));
    }

    @Test
    void aStuckSweepStillStartsTheHandOver() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        holding(mira, 6);
        holding(pip, 4);
        // Pip goes for a pass it never gets to.
        fetching(pip, true);

        AgentKpqCoordinator.tick(session, 2_000L);
        for (long nowMs = SET_OUT_MS; nowMs < SET_OUT_MS + SWEEP_LIMIT_MS; nowMs += 1_000L) {
            AgentKpqCoordinator.tick(session, nowMs);
        }
        modes.verify(() -> AgentModeService.startMoveTo(any(), eq(BEN_BY_CLOTO), anyBoolean()), never());

        AgentKpqCoordinator.tick(session, SET_OUT_MS + SWEEP_LIMIT_MS);
        AgentKpqCoordinator.tick(session, SET_OUT_MS + SWEEP_LIMIT_MS + 1_000L);

        modes.verify(() -> AgentModeService.startMoveTo(entry(2), BEN_BY_CLOTO, true));
        modes.verify(() -> AgentModeService.startMoveTo(entry(3), BEN_BY_CLOTO, true));
    }

    @Test
    void aPassTooFreshToPickUpYetHoldsTheHandOverBack() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        holding(mira, 6);
        MapItem fresh = mock(MapItem.class);
        when(fresh.getItemId()).thenReturn(AgentKpqDefinition.PASS_ITEM);
        floor.add(fresh);
        try (MockedStatic<AgentLootEligibility> eligibility = mockStatic(AgentLootEligibility.class)) {
            eligibility.when(() -> AgentLootEligibility.isWaitingForTargetAge(
                    any(), any(), any(), eq(fresh), anyLong(), anyLong())).thenReturn(true);

            AgentKpqCoordinator.tick(session, 2_000L);
            AgentKpqCoordinator.tick(session, SET_OUT_MS);
            AgentKpqCoordinator.tick(session, 5_000L);

            modes.verify(() -> AgentModeService.startMoveTo(any(), eq(BEN_BY_CLOTO), anyBoolean()), never());
            assertTrue(AgentKpqSessionRegistry.canLootPass(2));
        }
    }

    @Test
    void passesOnTheirWayAreNotMadeUpButLostOnesStillAre() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        holding(mira, 10);

        // Mira picked up all ten and climbs to Ben for well over the 7 s grace.
        for (long nowMs = 2_000L; nowMs <= 20_000L; nowMs += 2_000L) {
            AgentKpqCoordinator.tick(session, nowMs);
        }
        verify(inventoryGateway, never()).addItem(any(), anyInt(), anyShort());

        // She drops them at Ben's feet, and they expire there before he picks them up.
        at(mira, BEN_BY_CLOTO.x + 8, BEN_BY_CLOTO.y);
        AgentKpqCoordinator.tick(session, 21_000L);
        AgentKpqCoordinator.tick(session, 28_100L);

        verify(inventoryGateway, times(1))
                .addItem(ben, AgentKpqDefinition.PASS_ITEM, (short) 10);
    }

    @Test
    void oneThatCannotGetUpToTheLeaderLeavesThePassesByClotoAndSaysSo() {
        // Ben stands on Cloto's island, a little way from her.
        at(ben, 180, CLOTO.y);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        holding(mira, 10);
        Point miraByCloto = clotoApproach(session, mira);

        // They set out at 4.1 s, and Mira's route up never gets her near Ben, though there is one.
        AgentKpqCoordinator.tick(session, 2_000L);
        for (long nowMs = SET_OUT_MS; nowMs < SET_OUT_MS + HANDOVER_MS; nowMs += 10_000L) {
            AgentKpqCoordinator.tick(session, nowMs);
        }
        items.verify(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort()), never());
        modes.verify(() -> AgentModeService.startMoveTo(entry(2), miraByCloto, true), never());

        AgentKpqCoordinator.tick(session, SET_OUT_MS + HANDOVER_MS);
        modes.verify(() -> AgentModeService.startMoveTo(entry(2), miraByCloto, true));
        items.verify(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort()), never());

        // Cloto's approach she can get to; she leaves them there, on Ben's way.
        at(mira, miraByCloto.x, miraByCloto.y);
        AgentKpqCoordinator.tick(session, SET_OUT_MS + HANDOVER_MS + 5_000L);
        items.verify(() -> AgentScriptItemActionService.dropItem(
                entry(2), InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 10), times(1));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(mira, BY_CLOTO));
        assertFalse(said().contains(RIGHT_HERE), said()::toString);
        assertFalse(said().contains(AT_FEET), said()::toString);

        // Gone from the floor before Ben fetches them, they are made up 7 s later, long before the phase timeout.
        AgentKpqCoordinator.tick(session, SET_OUT_MS + HANDOVER_MS + 6_000L);
        AgentKpqCoordinator.tick(session, SET_OUT_MS + HANDOVER_MS + 6_000L + GRACE_MS);
        verify(inventoryGateway, times(1))
                .addItem(ben, AgentKpqDefinition.PASS_ITEM, (short) 10);
    }

    @Test
    void oneThatCanReachNeitherTheLeaderNorClotoLeavesThePassesWhereItIs() {
        // Ben fights below; Cloto's island cannot be reached from where Mira is stuck.
        at(ben, 900, -451);
        unreachable.add(CLOTOS_ISLAND);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        holding(mira, 10);

        AgentKpqCoordinator.tick(session, 2_000L);
        for (long nowMs = SET_OUT_MS; nowMs < SET_OUT_MS + HANDOVER_MS; nowMs += 10_000L) {
            AgentKpqCoordinator.tick(session, nowMs);
        }
        items.verify(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort()), never());
        AgentKpqCoordinator.tick(session, SET_OUT_MS + HANDOVER_MS);

        items.verify(() -> AgentScriptItemActionService.dropItem(
                entry(2), InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 10), times(1));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(mira, RIGHT_HERE));
    }

    @Test
    void staleFloorPassesDoNotHoldTheRecoveryBack() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        // Left 2,300 px under Ben at 1 s; nobody holds a pass, and nothing is left to fight.
        long droppedAtMs = 1_000L;
        MapItem left = pass(31, droppedAtMs, new Point(0, -451));
        floor.add(left);

        long nowMs = 2_000L;
        for (; nowMs < droppedAtMs + PATIENCE_MS; nowMs += 1_000L) {
            AgentKpqCoordinator.tick(session, nowMs);
        }
        assertFalse(said().contains(RECOLLECT), said()::toString);

        // Past the patience the nearest companion goes back for it, once, as a player would.
        AgentKpqCoordinator.tick(session, droppedAtMs + PATIENCE_MS);
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(mira, RECOLLECT));
        AgentKpqCoordinator.tick(session, droppedAtMs + PATIENCE_MS + 1_000L);
        assertEquals(AgentKpqMemberState.Role.STAGE5_PASS_CARRIER, session.member(2).role());
        loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(entry(2), left), atLeastOnce());
        loot.verify(() -> AgentGrindLootStateRuntime.clearObjectiveLootTarget(entry(2)), never());

        // It never gets there; the sweep's bound ends the re-collect, and the grace after it the wait.
        long recollectOverMs = droppedAtMs + PATIENCE_MS + SWEEP_LIMIT_MS;
        for (nowMs = droppedAtMs + PATIENCE_MS + 2_000L; nowMs < recollectOverMs + GRACE_MS; nowMs += 1_000L) {
            AgentKpqCoordinator.tick(session, nowMs);
        }
        verify(inventoryGateway, never()).addItem(any(), anyInt(), anyShort());
        // It let go of the pass it never reached, which would otherwise count as fetched for good.
        loot.verify(() -> AgentGrindLootStateRuntime.clearObjectiveLootTarget(entry(2)), times(1));
        AgentKpqCoordinator.tick(session, recollectOverMs + GRACE_MS);

        verify(inventoryGateway, times(1)).addItem(ben, AgentKpqDefinition.PASS_ITEM, (short) 10);
        assertEquals(1, said().stream().filter(RECOLLECT::equals).count(), said()::toString);
        // Passes made up from nothing: the carry's pace span is kept out of the calibration.
        assertTrue(session.pace().snapshot().stageFivePassesRestored());
    }

    @Test
    void stalePassesFetchedAgainAreBroughtToTheLeadersFeet() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        long droppedAtMs = 1_000L;
        floor.add(pass(31, droppedAtMs, new Point(0, -451)));
        for (long nowMs = 2_000L; nowMs <= droppedAtMs + PATIENCE_MS + 1_000L; nowMs += 1_000L) {
            AgentKpqCoordinator.tick(session, nowMs);
        }

        // Mira picks the ten up off the floor and walks them to Ben.
        floor.clear();
        holding(mira, 10);
        long pickedUpMs = droppedAtMs + PATIENCE_MS + 2_000L;
        AgentKpqCoordinator.tick(session, pickedUpMs);
        AgentKpqCoordinator.tick(session, pickedUpMs + 1_000L);
        modes.verify(() -> AgentModeService.startMoveTo(entry(2), BEN_BY_CLOTO, true));
        at(mira, BEN_BY_CLOTO.x + 4, BEN_BY_CLOTO.y);
        AgentKpqCoordinator.tick(session, pickedUpMs + 2_000L);

        items.verify(() -> AgentScriptItemActionService.dropItem(
                entry(2), InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 10), times(1));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(mira, RECOLLECTED));
        verify(inventoryGateway, never()).addItem(any(), anyInt(), anyShort());
    }

    @Test
    void aCarrierAboutToGoDownLeavesItsPassesAndAHealthyOneKeepsItsOwn() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        fighting(JR_NECKI);
        holding(mira, 4);
        holding(pip, 3);
        when(mira.getCurrentMaxHp()).thenReturn(500);
        when(pip.getCurrentMaxHp()).thenReturn(500);
        // Pip is at 5% with no HP potion left (its USE bag holds none).
        when(pip.getHp()).thenReturn(25);

        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, 3_000L);
        AgentKpqCoordinator.tick(session, 4_000L);

        items.verify(() -> AgentScriptItemActionService.dropItem(
                entry(3), InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 3), times(1));
        items.verify(() -> AgentScriptItemActionService.dropItem(eq(entry(2)), any(), anyInt(), anyShort()), never());
        assertEquals(1, said().stream().filter(LEAVING::equals).count(), said()::toString);
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(pip, LEAVING));
        // It picks none up again; Mira still does.
        assertFalse(AgentKpqSessionRegistry.canLootPass(3));
        assertTrue(AgentKpqSessionRegistry.canLootPass(2));
    }

    @Test
    void aCarrierLowOnHpWithPotionsLeftKeepsItsPasses() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        fighting(JR_NECKI);
        holding(pip, 3);
        when(pip.getCurrentMaxHp()).thenReturn(500);
        when(pip.getHp()).thenReturn(25);
        try (MockedStatic<AgentSupplyInventorySnapshot> supplies = mockStatic(AgentSupplyInventorySnapshot.class)) {
            supplies.when(() -> AgentSupplyInventorySnapshot.quantity(pip, AgentResourceCategory.HP_POTION))
                    .thenReturn(12);

            AgentKpqCoordinator.tick(session, 2_000L);
            AgentKpqCoordinator.tick(session, 3_000L);
        }

        items.verify(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort()), never());
        assertTrue(AgentKpqSessionRegistry.canLootPass(3));
    }

    @Test
    void theHandOverEachDropAndThePickupAreLogged() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        holding(mira, 6);
        holding(pip, 4);
        holding(ben, 0);
        try (KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class)) {
            AgentKpqCoordinator.tick(session, 2_000L);
            AgentKpqCoordinator.tick(session, SET_OUT_MS);
            at(mira, BEN_BY_CLOTO.x + 8, BEN_BY_CLOTO.y);
            at(pip, BEN_BY_CLOTO.x - 8, BEN_BY_CLOTO.y);
            AgentKpqCoordinator.tick(session, 5_000L);
            passesHeld.put(ben, 10);
            AgentKpqCoordinator.tick(session, 6_500L);

            String id = session.sessionId();
            assertEquals(List.of("KPQ Stage 5 hand-over start: session=" + id + " leaderMap="
                            + AgentKpqDefinition.STAGE_5_MAP + " leaderRegion=[6] leaderPos=(260,-2717) sweepCapped=false "
                            + "sinceReachedMs=2100 carriers=[Mira:passes=6:reach=true:pos=(0,-451), "
                            + "Pip:passes=4:reach=true:pos=(300,-451)]"),
                    log.startingWith("KPQ Stage 5 hand-over start: "));
            assertEquals(List.of(
                            "KPQ pass drop: session=" + id + " stage=5 agent=Mira reason=beside passes=6 dx=8 dy=0 "
                                    + "sameFoothold=true leaderFooting=GROUND",
                            "KPQ pass drop: session=" + id + " stage=5 agent=Pip reason=beside passes=4 dx=-8 dy=0 "
                                    + "sameFoothold=true leaderFooting=GROUND"),
                    log.startingWith("KPQ pass drop: "));
            assertEquals(List.of("KPQ pass pickup: session=" + id + " stage=5 leader=Ben delta=10 held=10 "
                            + "sinceDropMs=1500"),
                    log.startingWith("KPQ pass pickup: "));
        }
    }

    @Test
    void anEjectedCarriersPassesAreLogged() {
        Character tobi = onStageFive(run.companion(4, "Tobi"), new Point(600, -451));
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip, tobi);
        fighting(JR_NECKI);
        holding(mira, 2);
        holding(pip, 3);
        try (KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class)) {
            AgentKpqCoordinator.tick(session, 2_000L);
            // The stock respawn took Pip out of the event, and its passes with it; then it left the party.
            passesHeld.put(pip, 0);
            run.leaveParty(pip);
            AgentKpqCoordinator.tick(session, 3_000L);

            List<String> ejected = log.startingWith("KPQ member left the live party and was ejected: ");
            assertEquals(1, ejected.size());
            assertTrue(ejected.getFirst().endsWith(
                    " passesNow=0 passesLastSeen=3 carriedPasses={Mira=2, Tobi=0}"), ejected.getFirst());
        }
        assertEquals(3, session.stage5PassesLostWithMembers());
    }

    @Test
    void whatACarrierStillHoldsWhenStageFiveClearsIsDroppedThere() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        holding(mira, 3);
        when(run.event.getProperty("5stageclear")).thenReturn("true");

        AgentKpqCoordinator.tick(session, 2_000L);

        items.verify(() -> AgentScriptItemActionService.dropItem(
                entry(2), InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 3), times(1));
        assertEquals(AgentKpqSession.Phase.CLAIMING_REWARDS, session.phase());
    }

    @Test
    void companionsOnStageFiveBeforeTheLeaderPickUpPassesAndSweepTheRest() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_4, ben, mira, pip);
        when(ben.getMapId()).thenReturn(AgentKpqDefinition.STAGE_4_MAP);
        when(run.event.getProperty("4stageclear")).thenReturn("true");
        Monster necki = fighting(JR_NECKI).getFirst();

        // The first tick only staggers when each starts moving.
        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, 4_000L);

        assertEquals(AgentKpqMemberState.Role.STAGE5_PASS_CARRIER, session.member(2).role());
        assertTrue(AgentKpqSessionRegistry.canLootPass(2));

        when(necki.isAlive()).thenReturn(false);
        AgentKpqCoordinator.tick(session, 5_000L);

        lootTargets.verify(() -> AgentGrindLootTargetService.prepareNearestObjectiveItem(
                entry(2), mira, Set.of(AgentKpqDefinition.PASS_ITEM)), atLeast(1));
        assertEquals(AgentKpqSession.Phase.STAGE_4, session.phase());
    }

    @Test
    void kingSlimesLootDelayHoldsTheCarriersBackBeforeTheLeaderArrivesToo() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_4, ben, mira, pip);
        when(ben.getMapId()).thenReturn(AgentKpqDefinition.STAGE_4_MAP);
        when(run.event.getProperty("4stageclear")).thenReturn("true");
        Monster kingSlime = fighting(KING_SLIME).getFirst();
        // The pickup check reads the wall clock.
        long nowMs = System.currentTimeMillis();
        session.markProgress(nowMs);

        AgentKpqCoordinator.tick(session, nowMs);
        AgentKpqCoordinator.tick(session, nowMs + 2_000L);
        when(kingSlime.isAlive()).thenReturn(false);
        AgentKpqCoordinator.tick(session, nowMs + 2_100L);

        assertEquals(AgentKpqMemberState.Role.STAGE5_PASS_CARRIER, session.member(2).role());
        assertFalse(AgentKpqSessionRegistry.canLootPass(2));
    }

    @Test
    void theShoesWinnerGoesRightAfterKingSlimeWhenNoHumanIsNearThem() {
        AgentKpqSession session = shoesOnKingSlimesFloor();

        // King Slime's revive grace runs out at 4 s and the shoes are seen; his 3 s loot delay follows.
        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, SET_OUT_MS);
        AgentKpqCoordinator.tick(session, 7_200L);

        assertTrue(said().contains(GRAB_SHOES), said()::toString);
    }

    @Test
    void aHumanBesideTheShoesStillGetsTheFirstChance() {
        AgentKpqSession session = shoesOnKingSlimesFloor();
        at(ben, KING_SLIMES_FLOOR.x - 10, KING_SLIMES_FLOOR.y);

        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, SET_OUT_MS);
        AgentKpqCoordinator.tick(session, 7_200L);
        assertFalse(said().contains(GRAB_SHOES), said()::toString);

        AgentKpqCoordinator.tick(session, 11_200L);
        assertTrue(said().contains(GRAB_SHOES), said()::toString);
    }

    @Test
    void everyNewHandOverLineSoundsLikeAPlayer() {
        Pattern engine = Pattern.compile("->|\\d+\\s*/\\s*\\d+|%|~|(?i:dps|estimate)");
        for (String line : List.of(AgentKpqPassHandOver.routeLine("Ben"), AgentKpqPassHandOver.routeAgainLine("Ben"),
                AgentKpqPassHandOver.leftByClotoLine("Ben"), AgentKpqPassHandOver.recollectedLine("Ben"),
                AgentKpqPassHandOver.CURSE_EYES_CAREFUL, AgentKpqPassHandOver.LEAVING_PASSES,
                AgentKpqPassHandOver.RECOLLECT_START)) {
            assertFalse(engine.matcher(line).find(), line);
            assertEquals(line.substring(line.indexOf(' ') + 1).toLowerCase(),
                    line.substring(line.indexOf(' ') + 1), line);
        }
    }

    /** King Slime is down and his shoes lie on his floor; Ben holds no pass yet. */
    private AgentKpqSession shoesOnKingSlimesFloor() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        MapItem shoes = mock(MapItem.class);
        when(shoes.getItemId()).thenReturn(AgentKpqDefinition.SQUISHY_SHOES);
        when(shoes.getPosition()).thenReturn(KING_SLIMES_FLOOR);
        floor.add(shoes);
        return session;
    }

    private Point clotoApproach(AgentKpqSession session, Character companion) {
        return AgentKpqCoordinator.npcApproachPoint(session, session.member(companion.getId()), companion, CLOTO, 501);
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

    private void fetching(Character companion, boolean fetching) {
        lootTargets.when(() -> AgentGrindLootTargetService.prepareNearestObjectiveItem(
                entry(companion.getId()), companion, Set.of(AgentKpqDefinition.PASS_ITEM))).thenReturn(fetching);
    }

    private static MapItem pass(int objectId, long droppedAtMs, Point position) {
        MapItem drop = mock(MapItem.class);
        when(drop.getItemId()).thenReturn(AgentKpqDefinition.PASS_ITEM);
        when(drop.getObjectId()).thenReturn(objectId);
        when(drop.getDropTime()).thenReturn(droppedAtMs);
        when(drop.getPosition()).thenReturn(position);
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
        return at(member, position.x, position.y);
    }

    private static Character at(Character member, int x, int y) {
        when(member.getPosition()).thenReturn(new Point(x, y));
        return member;
    }

    private static AgentRuntimeEntry entry(int companionId) {
        return AgentRuntimeRegistry.findByAgentCharacterId(companionId);
    }

    private List<Monster> fighting(int... mobIds) {
        List<Monster> monsters = java.util.Arrays.stream(mobIds).mapToObj(Stage5PassCarryTest::monster).toList();
        when(run.map.getAllMonsters()).thenReturn(monsters);
        return monsters;
    }

    private static Monster monster(int mobId) {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(mobId);
        when(monster.isAlive()).thenReturn(true);
        return monster;
    }

    private static long tuning(String key) {
        return config.AgentTuning.longValue("server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator." + key);
    }
}
